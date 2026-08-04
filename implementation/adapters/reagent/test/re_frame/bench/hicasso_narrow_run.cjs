#!/usr/bin/env node
// EP-0038 P0 — the RATOM-SPINE NARROW-WRITE leg, write + flush SUMMED.
//
//   node implementation/adapters/reagent/test/re_frame/bench/hicasso_narrow_run.cjs
//   HN_ROUNDS=6 node .../hicasso_narrow_run.cjs --no-build
//
// Bead rf2-2rtt6.3. The bar, the budgets and the P0 table this feeds are
// operator-owned on rf2-2rtt6.1; workers append measurements and only the
// operator amends the numbers.
//
// WHAT IS BEING PRICED, AND WHY IT IS ONE NUMBER AND NOT TWO
// ----------------------------------------------------------
// On the ratom spine a write is a bare install into the frame's one
// physical container. The reactions do not recompute there; they recompute
// on Reagent's flush. So a harness that timed the write leg and stopped
// would publish a flatteringly small figure nobody experiences, and a
// harness that timed only the flush would miss the subscription graph
// entirely. The published figure is `write + gap + force`, SUMMED. The
// split is published beside it because the split is the whole point: the
// withdrawn predecessor's ~15x narrow row decomposed roughly 90% to the
// frame write and the signal graph rather than to rendering, and it was
// compared against a bare `reagent.core/atom` — its own idiom, but far
// less framework.
//
// NO BUILD-ID IS ADDED TO shadow-cljs.edn
// ---------------------------------------
// `implementation/shadow-cljs.edn` is hot-zone and rf2-2rtt6.2 owns the
// measurement lane's build-id. This driver therefore does what the donor's
// own b8 driver does: it takes an EXISTING `:advanced` `:browser` build as
// a config template and overrides the entry point and the output directory
// with `--config-merge`, so the repository gains no build id from this
// bead. The template is rf2-2rtt6.2's `:hicasso-bench`, which is the lane
// every Hicasso arm compiles through.
//
// THE INSTRUMENT'S OWN GATES, all of which run BEFORE any figure is taken
// ----------------------------------------------------------------------
//   1. the arm-order guard's self-test, replayed from recorded fixtures,
//      run INSIDE the :advanced bundle (the guard that adjudicates these
//      figures is the CLJC one this bundle carries);
//   2. the key-renaming integrity probe, likewise inside the bundle;
//   3. the measured `performance.now()` quantum, against which every leg
//      is checked — a leg sitting on the clamp is not a reading;
//   4. per-arm warm-up at FULL window size, continued until the guard's
//      own phase rule stops separating the trajectory's first third from
//      its last;
//   5. the positive control, predicted vs measured, every run — read
//      directly, as a slope across two rungs, and as the falsifiable
//      consequence that an arm's total must NOT move when the control's
//      size does;
//   6. the read-back gate's non-vacuity control: the same arm with an
//      EMPTY `flushSync` for its drain, which must go red;
//   7. the ladder-isolation gate: every rung mounts the same fixture, so a
//      rung moves the SUBSCRIPTION count and nothing else.
//
// AND THE GATES THAT NOW FAIL THE RUN RATHER THAN DECORATING IT
// -------------------------------------------------------------
// The audit of PR #7262 found the three central checks fail-OPEN: a stale
// write on a real arm was printed and stored, a broken leg identity was
// printed and stepped over, and the verification denominator counted 720
// windows of a pseudo-arm that renders no cell and forces `ok` true. So a
// run could print `VERDICT: reportable` beside a column saying some of its
// writes never reached the page. All three are exits now, and the
// denominator counts only arms that could have been verified.
//
// AND THE SAME DEFECT, FOUND TWICE MORE (rf2-rr6do)
// -------------------------------------------------
// Gates 3 and 4 above were in the same condition the audit of #7262 found
// the other three in: computed, printed, written into `report.json`, and
// never read by the verdict. An arm that hit `HN_WARMUP_MAX` still trending
// printed `still trending at the N-window ceiling` and the run went on to
// say `VERDICT: reportable.` — figures taken off a site that was still
// moving. A leg sitting on the clock quantum printed `CLAMP-LIMITED, not
// quotable as absolute` beside a table whose whole purpose is to quote
// absolutes. Both are exits now.
//
// EXIT CODES
//   0  reportable
//   1  a gate failed, or the run could not complete
//   2  THE ARM-ORDER GUARD REFUSED. No figure from this run may be
//      published. Repair the arm — more warm-up, more rounds, a quieter
//      box — never the guard's tolerance.
//   3  an arm never SETTLED inside the warm-up ceiling — it was measured on
//      a site still trending. Raise HN_WARMUP_MAX and re-run (rf2-rr6do,
//      the same refusal rf2-tb345 gave b8).
//   4  a quoted leg sits on the clock quantum, so the absolute table it
//      feeds is not a reading (rf2-rr6do). More writes per sample; not a
//      looser clamp.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const IMPL = path.resolve(__dirname, '../../../../..');
const REPO = path.resolve(IMPL, '..');
const OUT = path.join(IMPL, 'out', 'hicasso-narrow');
const PORT = Number(process.env.HN_PORT || 8141);

// THE LANE'S ONE CACHE RULE (rf2-2rtt6.20, applied here by rf2-2rtt6.22).
//
// This driver is one of the programs riding `:hicasso-bench`, and shadow-cljs
// derives the build cache directory from the build id alone — before any
// `--config-merge` is applied — so every program on the id shares ONE cache
// entry. There is deliberately no count here: a number goes stale the moment
// the next lane driver lands, and `git grep -l resetLaneBuildCache` names the
// current set exactly. The rule is that EVERY driver on the id clears the
// entry before it builds. The others already did, which is why this one could
// no longer poison THEM; nothing was clearing it on this driver's behalf, so
// it could still be handed a cache poisoned by whichever arm ran before it —
// until rf2-2rtt6.22 added the clear below. `lane_cache.cjs` carries the
// mechanism, the isolation evidence and the measured cost.
//
// Reached by path across the test trees, exactly as `navigate.cjs` is below
// and for the same reason: ONE helper for the repository, never a second copy
// per lane. Unlike `loadNavigate`, this require has NO FALLBACK — a fallback
// here would silently re-arm the trap, and a driver that cannot find the
// cache rule must fail loudly rather than measure without it. (The helper
// now sits beside `navigate.cjs` in the shared bench-helper directory,
// hoisted there by rf2-9smjn once a second tree needed it.)
const { resetLaneBuildCache } = require(
  path.join(IMPL, 'freehand/test/re_frame/freehand/bench/lane_cache.cjs')
);

// THE LANE'S ONE PAGE-FAILURE COLLECTOR (rf2-sib23), reached the same way and
// with NO FALLBACK for the same reason: a fallback would silently re-arm the
// fail-open this require exists to close. `sentinel.cjs` carries the finding —
// nine drivers, this one among them, installed a bare `pageerror` handler
// that printed and recorded nothing, so an uncaught throw was announced on
// stderr and the run exited 0 beneath it.
const { watchPage } = require(
  path.join(IMPL, 'freehand/test/re_frame/freehand/bench/sentinel.cjs')
);

// --- the plan ---------------------------------------------------------------
//
// Four rounds of six measured samples is 24 samples an arm, which is what
// the guard's phase rule needs: it splits an arm's trajectory into THIRDS
// and compares the first against the last, and a third has to carry a
// RANGE for the house rule to apply. At six samples a third is one sample
// and the question collapses to a bare ratio.
const ROUNDS = Number(process.env.HN_ROUNDS || 6);
const IN_ROUND_WARMUP = Number(process.env.HN_ROUND_WARMUP || 2);
const SAMPLES = Number(process.env.HN_SAMPLES || 6);
// Chrome clamps performance.now() to 100 us and one narrow write sits on
// that clamp. Twenty writes to a sample lifts every arm clear of it.
const WRITES = Number(process.env.HN_WRITES || 20);

// --- warm-up ---------------------------------------------------------------
//
// A measurement site reads well above its settled value until it has run
// several FULL-SIZE windows. The donor recorded the same control over
// sixteen consecutive windows with nothing varying but the call count:
//
//     42.32 | 10.32 10.26 10.26 10.26 10.33 10.28 | 8.12 8.12 ... 8.12
//
// the first window 5.3x settled, the next SIX at +27%. Position dominates
// adjacency, and warm-up matters more than interleaving. So each arm is
// warmed at its real window size, and warmed until it has stopped
// TRENDING rather than for a count somebody guessed: a floor of
// HN_WARMUP windows, then keep going while the first third of the
// trajectory still separates from the last third by more than the guard's
// own tolerance, to a ceiling of HN_WARMUP_MAX. The whole trajectory is
// printed, so "wide enough" is checkable rather than asserted.
//
// The settle test is the MEDIAN one and only the median one. A first cut
// accepted "the medians are within tolerance OR the ranges overlap", and on
// a box this noisy the ranges always overlap, so every arm settled at the
// floor while still visibly trending — `spine-replace` terminated on
// 0.98 0.89 0.37 0.33 0.46 0.56 0.48 0.59 0.66, which is not a settled
// site. Overlapping ranges are the right rule for ADJUDICATING two
// measured strata; they are the wrong rule for deciding a site has stopped
// moving, because a wide range makes the test pass by being uninformative.
const WARMUP = Number(process.env.HN_WARMUP || 8);
const WARMUP_MAX = Number(process.env.HN_WARMUP_MAX || 20);

// The guard's tolerance, and the donor's. It is NOT a knob for making a
// refusal go away: a refusal means the figure moved with the plan, and the
// repair is the arm.
const TOLERANCE = Number(process.env.HN_TOLERANCE || 0.10);

// The positive control's two rungs, in milliseconds of predicted burn per
// write. Both comfortably clear of the 100 us clamp; the difference is
// what cancels the clock-read overhead the direct reading contains.
const CTL_1 = Number(process.env.HN_CTL_1 || 0.3);
const CTL_2 = Number(process.env.HN_CTL_2 || 0.9);

// The build TEMPLATE.
//
// `implementation/shadow-cljs.edn` is hot-zone and rf2-2rtt6.2 owns the
// measurement lane's build id, so this bead adds none: it overrides
// `:hicasso-bench`'s entry point and output directory with
// `--config-merge`, which is the technique the donor's own b8 driver uses,
// and the repository gains no build id from this driver.
//
// This used to CHOOSE its template — `:hicasso-bench` when the checkout had
// it, `:freehand-release` otherwise — because the lane's build id had not
// landed yet. It has (rf2-2rtt6.2), so the fallback was unreachable code
// carrying a paragraph of reasoning for a branch that could no longer be
// taken, and rf2-uhw11 removes both. The donor template was never
// equivalent anyway: `:freehand-release` is CLEANED AND REBUILT by
// `test:freehand-reachability` and `test:freehand-matched`, so a bench run
// and a gate run raced the same `.shadow-cljs/builds/freehand-release`
// cache — and Closure's renaming for a build compiled fresh differs from
// the same build compiled with a sibling warm, which the donor measured at
// 4,075 bytes. A figure taken through the fallback would not have been
// comparable with one taken through the lane.
const BASE_BUILD = 'hicasso-bench';
const NO_BUILD = process.argv.includes('--no-build');

const CONFIG_MERGE =
  '{:output-dir "out/hicasso-narrow" :asset-path "." ' +
  ':modules {:main {:init-fn re-frame.bench.hicasso-narrow-app/-main}}}';

function build() {
  // Before anything reads the cache — see the note beside the require.
  if (resetLaneBuildCache(IMPL, BASE_BUILD)) {
    console.error(`[hn] cleared .shadow-cljs/builds/${BASE_BUILD} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[hn] building :advanced bundle from template :${BASE_BUILD} ...`);
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BASE_BUILD, '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[hn] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>HN</title></head>' +
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

// --- small statistics, kept here so the report is self-contained ------------

const num = (x, d = 4) => (Number.isFinite(x) ? x.toFixed(d) : String(x));

function summarise(xs) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return {
    n: s.length,
    min: s[0],
    p50: s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2,
    max: s[s.length - 1],
  };
}

// THE HOUSE RULE, in one function. Overlapping ranges mean
// indistinguishable — not "close", not "a small difference", and never a
// finding. Every comparison in this driver goes through it.
const disjoint = (a, b) => a && b && (a.min > b.max || b.min > a.max);

const rng = (s) => (s ? `${num(s.p50)} [${num(s.min)}-${num(s.max)}]` : '--');

// THE QUANTISATION-UNBIASED ESTIMATOR, and why the split needs one.
//
// Chrome coarsens `performance.now()` to a 100 us grid, so a leg shorter
// than the grid reads either 0 or 100 us depending on where the boundary
// happened to fall. Its MEDIAN is therefore 0 by construction — which is a
// precise wrong number, and precisely the kind this instrument exists to
// refuse. The MEAN is not: `floor(t1/q)*q - floor(t0/q)*q` has expectation
// exactly `t1 - t0` when the phase is uniform, so averaging over enough
// windows recovers a sub-quantum leg that no single window can see.
//
// So the two estimators do two different jobs and both are published:
// p50 + range adjudicates (the house rule needs a range, and a median is
// robust to the scheduler), and the mean carries the write-versus-flush
// SPLIT, which is the whole point of this bead and which a median cannot
// express when one of the legs is under the clock floor.
const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);

// ---------------------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  if (!NO_BUILD) build();
  const server = serve();

  const { chromium } = require('playwright');
  const browser = await chromium.launch({
    args: [
      // Scheduling only: these stop the headless tab being treated as
      // backgrounded. They change WHEN the browser is willing to run work,
      // not how long the synchronous work in the measured window takes.
      '--disable-background-timer-throttling',
      '--disable-renderer-backgrounding',
      '--disable-backgrounding-occluded-windows',
    ],
  });
  const version = browser.version();
  const RUNTIME =
    `Chromium ${version} / :advanced / goog.DEBUG=false / ` +
    `${os.platform()} ${os.release()} / ${os.cpus().length} logical CPUs`;

  const page = await browser.newPage();
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; HN')) console.error(t);
  });
  // THE PAGE'S OWN FAILURES, COLLECTED RATHER THAN PRINTED (rf2-sib23). The
  // array reaches `verdict` below, which is this driver's ONE seat for a
  // decision — the property that stopped its five earlier fail-opens growing
  // back, and the property this one bypassed entirely by never being read.
  // `HN_ERROR` does not cover it: React 19.2 routes an uncaught render error
  // to `reportError` instead of rethrowing, so the app's own catch never runs
  // and the page carries on to `HN_READY`. The sentinel wait below is
  // deliberately NOT converted to `watch.race` — that is rf2-f5roa's separate
  // "report it promptly" remedy, and the fault here is the page that throws
  // AND STILL reaches its sentinel, which the wait returns from normally.
  const watch = watchPage(page, 'hn');

  const { navigate, NAV_TIMEOUT_MS } = loadNavigate();
  await navigate(page, `http://127.0.0.1:${PORT}/`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the 5-minute wait for `window.HN_READY`',
  });
  await page.waitForFunction('window.HN_READY === true || window.HN_ERROR', null, {
    timeout: 5 * 60 * 1000,
  });
  const bootErr = await page.evaluate('window.HN_ERROR || null');
  if (bootErr) throw new Error(`page failed to initialise: ${bootErr}`);

  console.error(`[hn] runtime: ${RUNTIME}`);

  // --- gate 1: the arm-order guard's own self-test ------------------------
  // Inside the bundle, because the guard that adjudicates these figures is
  // the CLJC one this bundle carries. Its checks are fixtures replayed from
  // the recorded study, so this is deterministic, and a harness that gets
  // `false` may measure nothing.
  const guardOk = await page.evaluate(() => window.HN.guardSelfTest());
  if (!guardOk) {
    throw new Error('order-guard self-test FAILED — nothing may be measured');
  }
  console.error('[hn] gate 1 ok — order-guard self-test passed inside the :advanced bundle');

  // --- gate 2: does the bundle read the keys it writes? -------------------
  // Closure's advanced renaming does not touch a quoted string key but DOES
  // rename a `(.-foo o)` accessor. The failure is silent for `bad`: an
  // undefined slot plus one is NaN, the literal's zero stands, and the
  // driver reads ZERO unverified writes for ever.
  const integ = await page.evaluate(() => window.HN.integrity());
  const wantKeys = ['control', 'write', 'gap', 'force', 'total', 'span', 'bad', 'writes'];
  const gotKeys = integ && typeof integ.keys === 'string' ? integ.keys.split(',') : [];
  const missing = wantKeys.filter((k) => !gotKeys.includes(k));
  if (!integ || integ.control !== 7 || integ.bad !== 5 || integ.total !== -11 || missing.length) {
    throw new Error(
      `integrity probe FAILED — the :advanced bundle does not read the keys it writes. ` +
        `got ${JSON.stringify(integ)}; expected control=7 bad=5 total=-11; ` +
        `missing keys ${JSON.stringify(missing)}`
    );
  }
  console.error(`[hn] gate 2 ok — integrity probe, keys ${gotKeys.join(',')}`);

  // --- gate 3: what can this clock actually resolve? ----------------------
  const quantum = await page.evaluate(() => window.HN.quantum());
  if (!(quantum > 0)) throw new Error('could not measure the performance.now() quantum');
  console.error(
    `[hn] gate 3 ok — performance.now() quantum ${num(quantum, 5)} ms; ` +
      `a SAMPLE is ${WRITES} writes, so a leg is quoted only if the sample carries it clear of this`
  );

  const arms = await page.evaluate(() => window.HN.arms);
  const cellsN = await page.evaluate(() => window.HN.cellsN);
  const ladder = await page.evaluate(() => window.HN.ladder);
  const skipVerify = await page.evaluate(() => window.HN.skipVerify);
  const subsOf = await page.evaluate(() => window.HN.subs);
  if (ladder[ladder.length - 1] !== cellsN) {
    throw new Error(
      `the ladder's top rung (${ladder[ladder.length - 1]}) is not the headline cell count ` +
        `(${cellsN}) — the ladder would be fitted through a point no arm measured`
    );
  }
  console.error(`[hn] arms: ${arms.join(', ')}  (${cellsN} cells, ${WRITES} writes/sample)`);
  console.error(
    `[hn] skip-verify arms (excluded from the read-back denominator): ` +
      `${skipVerify.length ? skipVerify.join(', ') : 'none'}`
  );

  // --- gate 3b: the ladder isolates SUBSCRIPTIONS, not fixture size -------
  // The audit's first correction. Every rung must mount the same cell count
  // so a rung moves the subscription count and nothing else; a rung that
  // also moved the app-db vector, the component count and the DOM span
  // count would be a fixture-size ladder wearing a subscription ladder's
  // label, which is exactly what the first cut published.
  const rungArmName = (n) => (n === cellsN ? 'spine-replace' : `spine-${n}`);
  {
    const wrong = [];
    for (const n of ladder) {
      const a = rungArmName(n);
      if (subsOf[a] !== n) wrong.push(`${a} subscribes ${subsOf[a]}, expected ${n}`);
    }
    if (wrong.length) {
      throw new Error(
        `the subscription ladder is mis-wired — ${wrong.join('; ')}. ` +
          `The ladder may not be fitted until each rung's subscription count is its own.`
      );
    }
    console.error(
      `[hn] gate 3b ok — every ladder rung mounts ${cellsN} cells and ${cellsN} components; ` +
        `only the subscription count moves (${ladder.join('/')})`
    );
  }

  // --- gate 4: the read-back gate's non-vacuity control -------------------
  // The same arm with an EMPTY `flushSync` for its drain. An empty flush
  // drains only React's sync lane; a Reagent ratom write is not on it. So
  // the clock stops on the OLD value and the DOM read-back must catch it.
  // A gate that cannot go red has not verified anything.
  await page.evaluate((d) => window.HN.prepare(d), 0);
  const neg = await page.evaluate((w) => window.HN.negControl(w), WRITES);
  const negRate = neg.bad / neg.writes;
  console.error(
    `[hn] gate 4 — EMPTY-flushSync negative control: ${neg.bad} unverified of ${neg.writes} ` +
      `(${(negRate * 100).toFixed(0)}%)`
  );
  if (negRate < 0.5) {
    throw new Error(
      `read-back gate is VACUOUS — an arm whose drain is an empty flushSync should leave the ` +
        `cell stale, and only ${neg.bad} of ${neg.writes} writes were caught. ` +
        `Until this goes red, "0 unverified" on the real arms is not evidence.`
    );
  }
  console.error('[hn] gate 4 ok — the DOM read-back catches a stale commit');

  // --- warm-up ------------------------------------------------------------
  await page.evaluate((d) => window.HN.prepare(d), CTL_1);
  const trajectories = {};
  const settled = {};
  for (const arm of arms) {
    const xs = [];
    settled[arm] = false;
    for (let i = 0; i < WARMUP_MAX; i++) {
      xs.push(await page.evaluate(([a, w]) => window.HN.warm(a, w), [arm, WRITES]));
      if (i + 1 >= WARMUP) {
        const t = Math.max(1, Math.floor(xs.length / 3));
        const first = summarise(xs.slice(0, t));
        const last = summarise(xs.slice(-t));
        const ratio = last.p50 === 0 ? 1 : first.p50 / last.p50;
        // Settled = the first third of the trajectory no longer reads
        // differently from the last third, by MEDIAN. See the note on
        // WARMUP above for why the range test is not offered here.
        if (Math.abs(ratio - 1) <= TOLERANCE) {
          settled[arm] = true;
          break;
        }
      }
    }
    trajectories[arm] = xs;
    console.error(
      `[hn] warm ${arm.padEnd(17)} ${xs.map((x) => num(x, 3)).join(' ')}` +
        `   ${settled[arm] ? 'SETTLED' : `still trending at the ${WARMUP_MAX}-window ceiling`}`
    );
  }

  // --- the measured pass, at control rung 1 -------------------------------
  await page.evaluate(() => window.HN.reset());
  await page.evaluate((d) => window.HN.prepare(d), CTL_1);
  const pass1 = [];
  let pos = 0;
  for (let r = 0; r < ROUNDS; r++) {
    const out = await page.evaluate(
      ([w, s, n, p]) => window.HN.round(w, s, n, p),
      [IN_ROUND_WARMUP, SAMPLES, WRITES, pos]
    );
    pos = out.next;
    pass1.push(...out.samples);
    console.error(`[hn] round ${r + 1}/${ROUNDS} — ${out.samples.length} samples, ${out.bad} unverified`);
    await sleep(50);
  }

  const report = await page.evaluate((t) => window.HN.report(t), TOLERANCE);

  // --- the control ladder, at rung 2 --------------------------------------
  // Two rungs so the control has a SLOPE, which cancels the clock-read
  // overhead the direct reading contains — and so the falsifiable
  // consequence can be checked: an arm's total must NOT move when the
  // control's size does.
  await page.evaluate(() => window.HN.reset());
  await page.evaluate((d) => window.HN.prepare(d), CTL_2);
  const pass2 = [];
  const LADDER_ROUNDS = Math.max(2, Math.floor(ROUNDS / 2));
  for (let r = 0; r < LADDER_ROUNDS; r++) {
    const out = await page.evaluate(
      ([w, s, n, p]) => window.HN.round(w, s, n, p),
      [IN_ROUND_WARMUP, SAMPLES, WRITES, pos]
    );
    pos = out.next;
    pass2.push(...out.samples);
  }
  console.error(`[hn] control ladder — ${pass2.length} samples at ${CTL_1} -> ${CTL_2} ms`);

  const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
  watch.dispose();
  await browser.close();
  server.close();

  // =========================================================================
  // The report
  // =========================================================================

  const byArm = (rows, arm) => rows.filter((s) => s.arm === arm);
  const legOf = (rows, leg) => summarise(rows.map((s) => s[leg]));

  const lines = [];
  const say = (s) => {
    lines.push(s);
    console.log(s);
  };

  say('');
  say('=========================================================================');
  say('P0 — RATOM-SPINE NARROW WRITE (write + flush, SUMMED)   bead rf2-2rtt6.3');
  say('=========================================================================');
  say(`runtime          ${RUNTIME}`);
  say(`fixture          ${cellsN} cells, one layer-1 subscription per cell`);
  say(
    `ladder fixture   every rung mounts ${cellsN} cells / ${cellsN} components / ${cellsN} spans; ` +
      `only ${ladder.join('/')} of them SUBSCRIBE`
  );
  say(`sample           ${WRITES} narrow writes; per-write = sample / ${WRITES}`);
  say(`plan             ${ROUNDS} rounds x (${IN_ROUND_WARMUP} in-round warmup + ${SAMPLES} samples), arms interleaved`);
  say(`clock quantum    ${num(quantum, 5)} ms (measured in-page)`);
  say(
    `warm-up          ${Object.entries(settled)
      .map(([a, s]) => `${a}:${trajectories[a].length}${s ? '' : '*'}`)
      .join(' ')}   (* = still trending at the ${WARMUP_MAX}-window ceiling)`
  );
  say(`window           t0 -> control -> WRITE -> microtask -> FLUSH -> t3 -> DOM read-back`);
  say(`published figure t3 - t_control  =  write + gap + force`);
  say('');

  // --- the headline table -------------------------------------------------
  say('PER-WRITE MILLISECONDS — p50 [min-max] across all measured samples');
  say('');
  say('  arm               write+flush            write                  flush');
  const headline = {};
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const tot = legOf(rows, 'total');
    const wr = legOf(rows, 'write');
    const fo = legOf(rows, 'force');
    const gp = legOf(rows, 'gap');
    headline[arm] = { total: tot, write: wr, force: fo, gap: gp };
    say(`  ${arm.padEnd(17)} ${rng(tot).padEnd(22)} ${rng(wr).padEnd(22)} ${rng(fo)}`);
  }
  say('');
  say('  NO write-share column here, deliberately. The write leg is under the 100 us grid on');
  say('  every arm, so its MEDIAN is 0 by construction and a share computed from it reads a');
  say('  flat 0% — a precise wrong number. The split is taken from the mean table below.');
  say('');
  say('  (the microtask gap is priced separately and is expected to be ~0 on every arm here:');
  say('   a reagent.core/flush is already on React\'s sync lane, so no arm needs the boundary.)');
  for (const arm of arms) {
    say(`     gap  ${arm.padEnd(17)} ${rng(headline[arm].gap)}`);
  }
  say('');

  // --- the split ----------------------------------------------------------
  // The median above reads 0 for any leg under the 100 us grid, which is a
  // precise wrong number rather than a small one. The mean is unbiased under
  // that coarsening, so the SPLIT — the question this bead exists to answer
  // — is taken from it, with the median table above left standing as the
  // adjudication instrument.
  say('WRITE vs FLUSH — from the quantisation-unbiased MEAN over every measured write');
  say('');
  say('  arm               write+flush     write           flush           write share');
  const split = {};
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const t = mean(rows.map((s) => s.total));
    const w = mean(rows.map((s) => s.write));
    const g = mean(rows.map((s) => s.gap));
    const f = mean(rows.map((s) => s.force));
    split[arm] = { total: t, write: w, gap: g, force: f, writeShare: t > 0 ? w / t : null };
    const share = t > 0 ? `${((w / t) * 100).toFixed(1)}%` : '--';
    say(
      `  ${arm.padEnd(17)} ${num(t).padEnd(15)} ${num(w).padEnd(15)} ${num(f).padEnd(15)} ${share}`
    );
  }
  say('');
  // An arithmetic identity, not a check of the substrate: the three legs
  // are read off the SAME four clock samples the total is, so they must sum
  // to it exactly. A discrepancy means the accumulator is mis-wired.
  let identityOk = true;
  for (const arm of arms) {
    const s = split[arm];
    const err = Math.abs(s.write + s.gap + s.force - s.total);
    if (err > 1e-9) {
      identityOk = false;
      say(`  IDENTITY FAILED on ${arm}: write+gap+force - total = ${err}`);
    }
  }
  if (identityOk) say('  write + gap + force = write+flush exactly, on every arm (leg accounting intact)');
  say('');

  // --- what the flush is actually made of ---------------------------------
  // "The flush" is one word doing two jobs: Reagent re-running every
  // dirtied reaction, and React committing the one cell that changed. A
  // single number cannot separate them, and the separation is the whole
  // question — the withdrawn predecessor's narrow row was wrong precisely
  // because a framework cost was read as a rendering cost.
  //
  // The rungs separate them by construction — and "by construction" is now
  // literal. Every rung mounts the SAME 300-cell fixture: 300 cells in
  // app-db, 300 mounted components, 300 DOM spans. The rung's number is how
  // many of those cells hold a subscription, and writes land inside that
  // range, so the React commit is one cell at every rung. The slope is
  // therefore the per-subscription recompute and the intercept is
  // everything else.
  //
  // The first cut did NOT do this: a rung was a whole smaller fixture, and
  // the slope described fixture size. Gate 3b above is what stops that
  // coming back.
  const rungs = ladder
    .map((n) => ({ n, arm: rungArmName(n), split: split[rungArmName(n)] }))
    .filter((r) => r.split);
  let flushComposition = null;
  if (rungs.length >= 2) {
    say('WHERE THE FLUSH GOES — the subscription-count ladder');
    say('');
    say(`  Every rung mounts ${cellsN} cells, ${cellsN} components and ${cellsN} DOM spans. Only the`);
    say('  number of them that SUBSCRIBE moves, and writes land on a subscribing cell, so the');
    say('  React commit is the same one-cell commit at every rung.');
    say('');
    say('  subscriptions   flush ms/write   per subscription   local slope vs the rung below');
    for (let i = 0; i < rungs.length; i++) {
      const r = rungs[i];
      const each = `${num((r.split.force / r.n) * 1000, 3)} us`;
      let local = '--';
      if (i > 0) {
        const p = rungs[i - 1];
        local = `${num(((r.split.force - p.split.force) / (r.n - p.n)) * 1000, 3)} us/sub`;
      }
      say(
        `  ${String(r.n).padStart(13)}   ${num(r.split.force).padEnd(15)} ${each.padEnd(18)} ${local}`
      );
    }
    say('');

    // The linear model, and whether the rungs will carry it. A per-unit
    // cost quoted from two points is only a per-unit cost if a line
    // through them has a NON-NEGATIVE intercept: the intercept is the
    // work that does not scale with the subscription count — React's
    // one-cell commit and Reagent's drain overhead — and that work cannot
    // cost less than nothing. A negative intercept is the data refusing
    // the model, not a rounding error, and this ladder produced one at
    // two rungs (30 -> 300 fitted 2.27 us/sub with an intercept of
    // -0.048 ms).
    const lo = rungs[0];
    const hi = rungs[rungs.length - 1];
    const slope = (hi.split.force - lo.split.force) / (hi.n - lo.n);
    const intercept = lo.split.force - slope * lo.n;
    const growth = lo.split.force > 0 ? hi.split.force / lo.split.force : null;
    const scale = hi.n / lo.n;
    const exponent = growth ? Math.log(growth) / Math.log(scale) : null;
    flushComposition = {
      rungs: rungs.map((r) => ({ n: r.n, flushMs: r.split.force })),
      slopeMsPerSub: slope,
      interceptMs: intercept,
      exponent,
      linear: intercept >= 0,
    };
    if (intercept >= 0) {
      say(`  linear fit over ${lo.n}-${hi.n}: ${num(slope * 1000, 3)} us per subscription,`);
      say(`  intercept ${num(intercept)} ms — React's one-cell commit and Reagent's drain overhead.`);
      say(
        `  => of ${num(hi.split.force)} ms of flush at ${hi.n} cells, ` +
          `${num(slope * hi.n)} ms is the subscription graph.`
      );
    } else {
      say(`  NO PER-SUBSCRIPTION COST IS QUOTED. A line through ${lo.n} and ${hi.n} has an`);
      say(`  intercept of ${num(intercept)} ms, and the work that does NOT scale with the`);
      say('  subscription count — React\'s one-cell commit, Reagent\'s drain overhead —');
      say('  cannot cost less than nothing. The flush grows FASTER than the count:');
      say(
        `  ${scale.toFixed(1)}x the subscriptions costs ${growth.toFixed(1)}x the flush, ` +
          `an exponent of ${exponent.toFixed(2)}.`
      );
      say('  The per-rung and local-slope columns above are the honest reading; a single');
      say('  "us per subscription" figure would be a precise wrong number.');
    }
    say('');
    say('  What is NOT in doubt is the shape. A one-cell write marks EVERY layer-1');
    say('  subscription in the frame dirty and re-evaluates all of them: a layer-1 body is');
    say('  an opaque fn of the whole app-db and the graph holds no path. That is intrinsic');
    say('  and universal; what this ladder prices is what it costs on the ratom spine.');
    say('');
  }

  // --- the like-for-like correction ---------------------------------------
  // The reason this bead exists. The predecessor's narrow row pitted a
  // re-frame application against a bare `reagent.core/atom` and read the
  // difference as a substrate cost. Both arms are here, measured in the
  // same interleave on the same page, so the size of that framing error is
  // a row rather than a claim.
  say('LIKE-FOR-LIKE — what the comparison arm was, and what it should have been');
  say('');
  {
    const b = split['bare-ratom'];
    const s = split['spine-replace'];
    const d = split['spine-dispatch'];
    const rb = summarise(byArm(pass1, 'bare-ratom').map((x) => x.total));
    const rs = summarise(byArm(pass1, 'spine-replace').map((x) => x.total));
    const rd = summarise(byArm(pass1, 'spine-dispatch').map((x) => x.total));
    say(`  bare r/atom + cursor (no re-frame)          ${num(b.total)} ms/write   ${rng(rb)}`);
    say(`  re-frame on the ratom spine, replace-app-db ${num(s.total)} ms/write   ${rng(rs)}`);
    say(`  re-frame on the ratom spine, dispatch-sync  ${num(d.total)} ms/write   ${rng(rd)}`);
    say('');
    say(
      `  a narrow write costs ${(s.total / b.total).toFixed(1)}x more on re-frame-over-Reagent than on ` +
        `bare Reagent,`
    );
    say(
      `  and ${(d.total / b.total).toFixed(1)}x through the event drain. Any narrow-update ratio quoted against the`
    );
    say('  bare-ratom column is measuring that gap and calling it something else.');
    say(
      `  The event drain itself is ${num(d.total - s.total)} ms/write — the difference between the two doors.`
    );
  }
  say('');

  // --- both orders --------------------------------------------------------
  // slot-order rotates AND reflects on the sample index, so every arm is
  // measured under two different adjacencies. Reported as two ranges, and
  // adjudicated by the house rule.
  say('BOTH ORDERS — the same figure under the forward and the reflected slot order');
  say('');
  say('  arm               forward                reflected              verdict');
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const f = summarise(rows.filter((s) => s.order === 'forward').map((s) => s.total));
    const r = summarise(rows.filter((s) => s.order === 'reflected').map((s) => s.total));
    const v = disjoint(f, r) ? 'DISJOINT — the order moved it' : 'overlapping — indistinguishable';
    say(`  ${arm.padEnd(17)} ${rng(f).padEnd(22)} ${rng(r).padEnd(22)} ${v}`);
  }
  say('');

  // --- DOM verification ---------------------------------------------------
  say('DOM READ-BACK — every measured write read out of the page inside its own window');
  say('');
  // THE DENOMINATOR COUNTS ONLY WRITES THAT COULD HAVE BEEN VERIFIED.
  // `:instrument` renders no cell and forces `ok` true; folding its windows
  // in inflated the published denominator from 3,600 to 4,320 and let the
  // claim "every write read out of the DOM" stand over 720 writes that were
  // never looked at. Skipped arms get their own line and no credit.
  let badTotal = 0;
  let writeTotal = 0;
  let skippedTotal = 0;
  const badByArm = {};
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const bad = rows.reduce((a, s) => a + s.bad, 0);
    const n = rows.length * WRITES;
    badByArm[arm] = bad;
    if (skipVerify.includes(arm)) {
      skippedTotal += n;
      say(`  ${arm.padEnd(17)} ${n} writes NOT VERIFIABLE (renders no cell) — excluded from the tally`);
      continue;
    }
    badTotal += bad;
    writeTotal += n;
    say(`  ${arm.padEnd(17)} ${bad} unverified of ${n}`);
  }
  say(`  ${'VERIFIABLE ARMS'.padEnd(17)} ${badTotal} unverified of ${writeTotal}`);
  say(
    `  ${'(excluded)'.padEnd(17)} ${skippedTotal} writes on ${skipVerify.join(', ') || 'no arm'} — ` +
      `a forced ok is not a verified write`
  );
  say(
    `  negative control  ${neg.bad} unverified of ${neg.writes} — an EMPTY flushSync, which MUST go red`
  );
  say('');

  // --- the positive control -----------------------------------------------
  say('POSITIVE CONTROL — a predicted burn, read three ways');
  say('');
  const ctlRows = (rows) => summarise(rows.filter((s) => s.arm !== 'instrument').map((s) => s.control));
  const c1 = ctlRows(pass1);
  const c2 = ctlRows(pass2);
  say(`  rung 1   predicted ${num(CTL_1)} ms/write   measured ${rng(c1)}`);
  say(`  rung 2   predicted ${num(CTL_2)} ms/write   measured ${rng(c2)}`);
  const predSlope = CTL_2 - CTL_1;
  const measSlope = c2.p50 - c1.p50;
  const slopeErr = Math.abs(measSlope / predSlope - 1);
  say(
    `  slope    predicted ${num(predSlope)} ms          measured ${num(measSlope)} ms   ` +
      `(${(slopeErr * 100).toFixed(1)}% off; the slope cancels the clock-read overhead the`
  );
  say('           direct reading carries, so it is the stricter of the two)');
  say('');
  say('  falsifiable consequence — an arm\'s write+flush must NOT move when the control does:');
  let leaked = false;
  for (const arm of arms) {
    const a = legOf(byArm(pass1, arm), 'total');
    const b = legOf(byArm(pass2, arm), 'total');
    const bad = disjoint(a, b);
    if (bad) leaked = true;
    say(
      `    ${arm.padEnd(17)} rung1 ${rng(a).padEnd(22)} rung2 ${rng(b).padEnd(22)} ` +
        `${bad ? 'DISJOINT — LEAK' : 'overlapping — no leak'}`
    );
  }
  say('');

  // --- clamp check --------------------------------------------------------
  say('CLAMP CHECK — a leg sitting on the clock quantum is not a reading');
  say('');
  let clamped = [];
  for (const arm of arms) {
    if (arm === 'instrument') continue;
    for (const leg of ['total', 'write', 'force']) {
      const s = legOf(byArm(pass1, arm), leg);
      const perSample = s.p50 * WRITES;
      const mult = perSample / quantum;
      if (mult < 10) clamped.push(`${arm}/${leg} (${mult.toFixed(1)}x quantum per sample)`);
    }
  }
  if (clamped.length) {
    say(`  CLAMP-LIMITED, not quotable as absolute: ${clamped.join(', ')}`);
  } else {
    say(`  every quoted leg carries at least 10x the quantum per sample — clear of the clamp`);
  }
  say('');

  // --- the guard ----------------------------------------------------------
  // What the guard could actually see. The phase factor splits an arm's
  // trajectory into THIRDS by position, so a run that lost its positions
  // would silently adjudicate a third of the samples it claims to hold —
  // an `[ok]` verdict taken on evidence that was never there. The counts
  // are printed so the strata sizes below are checkable against them.
  say('WHAT THE GUARD SAW');
  say('');
  let positionsLost = false;
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const fin = rows.filter((s) => Number.isFinite(s.position));
    const ps = fin.map((s) => s.position);
    if (fin.length !== rows.length) positionsLost = true;
    say(
      `  ${arm.padEnd(17)} ${rows.length} samples, ${fin.length} with a finite position` +
        (ps.length ? ` [${Math.min(...ps)}-${Math.max(...ps)}]` : '') +
        `, thirds of ${Math.max(1, Math.floor(fin.length / 3))}`
    );
  }
  say('');
  for (const l of report.lines) say(l);
  say('');

  // --- the EDN artefact ---------------------------------------------------
  const rev = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: REPO, encoding: 'utf8' });
  const sha = rev.status === 0 ? rev.stdout.trim() : 'unknown';
  const artefact = {
    bead: 'rf2-2rtt6.3',
    sha,
    runtime: RUNTIME,
    quantum,
    plan: {
      rounds: ROUNDS,
      inRoundWarmup: IN_ROUND_WARMUP,
      samples: SAMPLES,
      writes: WRITES,
      cells: cellsN,
      tolerance: TOLERANCE,
    },
    warmup: trajectories,
    warmupSettled: settled,
    ladder,
    flushComposition,
    control: { rung1: CTL_1, rung2: CTL_2, measured1: c1, measured2: c2 },
    negativeControl: neg,
    headline,
    split,
    samples: { rung1: pass1, rung2: pass2 },
    unverified: { bad: badTotal, of: writeTotal, skipped: skippedTotal, byArm: badByArm },
    legIdentity: identityOk,
    guard: { refuse: report.refuse, contaminated: report.contaminated, unchecked: report.unchecked },
    edn: report.edn,
  };
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(artefact, null, 2));
  fs.writeFileSync(path.join(OUT, 'report.txt'), lines.join('\n') + '\n');
  say(`producing commit  ${sha}`);
  say(`reproduce         node implementation/adapters/reagent/test/re_frame/bench/hicasso_narrow_run.cjs`);
  say(`artefacts         implementation/out/hicasso-narrow/report.{json,txt}`);
  say('');

  // --- the verdict --------------------------------------------------------
  const v = verdict({
    pageErrors,
    positionsLost,
    orderRefuse: report.refuse,
    leaked,
    badTotal,
    writeTotal,
    offenders: arms
      .filter((a) => !skipVerify.includes(a) && badByArm[a] > 0)
      .map((a) => `${a}:${badByArm[a]}`)
      .join(' '),
    identityOk,
    warmupUnsettled: arms.filter((a) => !settled[a]),
    warmupMax: WARMUP_MAX,
    clamped,
  });
  for (const line of v.lines) say(line);
  if (v.code !== 0) process.exitCode = v.code;
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// ONE pure function over the run's summary, which is what makes this exit
// path checkable without a release build and a headless Chromium — see
// `hicasso_narrow_exit_path.test.cjs`. It is also what stops the defect
// growing back: the way all five of this driver's earlier fail-opens grew
// was a second reading of a computed condition somewhere below the report,
// and there is now only one place a condition can be read.
//
// Every condition is INDEPENDENT — each refuses on its own, and when several
// fire every one of them is named, so a run is never told about one fault
// while a second stays hidden until the first is repaired.
//
// PRECEDENCE IS THE EXISTING ONE, AND THE TWO NEW REFUSALS SIT LAST. A run
// that exited 1 before still exits 1; a run the arm-order guard refused
// still exits 2. 3 and 4 can therefore only change the verdict of a run that
// was previously being called `reportable.`, which is precisely the set
// rf2-rr6do is about.
//
//   3  AN UNSETTLED WARM-UP. `settled[arm]` was computed inside the
//      `WARMUP_MAX` loop, printed twice — once per arm, once in the
//      `warm-up` header line with a `*` — and stored as `warmupSettled` in
//      report.json. Nothing read it. An arm that reached the ceiling still
//      trending had its figures taken off a moving site, and "still
//      trending" printed above "reportable." is not a gate.
//
//   1  ALSO an uncaught `pageerror` (rf2-sib23). It takes the code this
//      driver already had for "the run did not measure what it claims to
//      have measured", so no run's code changes; what changes is that a run
//      whose page THREW can no longer be called `reportable.` The signal was
//      collected by nobody until now — a bare handler printed it — and the
//      app cannot supply it, because React does not rethrow an uncaught
//      render error to the caller of `flushSync`.
//
//   4  A CLAMP-LIMITED LEG, scoped deliberately narrower than the others.
//      This driver exists to publish ABSOLUTE per-write milliseconds and the
//      write/flush split taken from the same legs; a leg carrying less than
//      10x the measured clock quantum per sample is a reading of the clock,
//      not of the arm, and the run itself says so — `not quotable as
//      absolute`. The repair is more writes per sample (HN_WRITES), which
//      lifts the per-sample figure off the quantum. It is NOT a looser
//      multiple: the clamp is a property of `performance.now()` on the box,
//      and a threshold moved until it passes has stopped being a threshold.
function verdict(s) {
  const lines = [];
  const say = (...xs) => lines.push(...xs);

  // AN UNCAUGHT PAGE ERROR, FIRST AND WITH THE EXISTING CODE 1 (rf2-sib23).
  // It sits at the head because it is not a judgement about a figure — it
  // says the figures are not of the page they name — and it takes 1 rather
  // than a new number because that is already this driver's code for "the
  // run did not measure what it claims to have measured".
  const pageErrors = s.pageErrors || [];
  if (pageErrors.length) {
    say(
      'VERDICT: FAILED — the page threw and kept going, so every figure above was taken on a',
      '         page that is not the page under test. No window.HN_ERROR is set for this:',
      '         React does not rethrow an uncaught render error to the caller of flushSync',
      '         (see sentinel.cjs). Re-run on a clean page.',
      ...pageErrors.map((e) => `         ${e}`)
    );
  }
  if (s.positionsLost) {
    say(
      'VERDICT: FAILED — some samples reached the guard with no finite position, so the',
      '         phase factor was adjudicated on less evidence than it reported. Nothing',
      '         above may be published until every sample carries its position.'
    );
  }
  if (s.orderRefuse) {
    say(
      'VERDICT: REFUSED by the arm-order guard. No figure above may be published.',
      '         Repair the arm — more warm-up, more rounds, a quieter box. Not the tolerance.'
    );
  }
  if (s.leaked) {
    say("VERDICT: FAILED — an arm's total moved with the control size. The leg accounting leaks.");
  }
  // A stale write on a REAL arm was printed and stored and nothing else: a
  // run could report `VERDICT: reportable` beside a column saying some of
  // its writes never reached the page. The read-back gate is the reason the
  // figures above are about a page rather than about a clock, so it fails
  // the run.
  if (s.badTotal > 0) {
    say(
      `VERDICT: FAILED — ${s.badTotal} of ${s.writeTotal} measured writes never reached the DOM`,
      `         (${s.offenders}). A window that did not commit is not a measurement of one.`
    );
  }
  // The three legs are read off the SAME four clock samples the total is, so
  // they must sum to it exactly. A discrepancy means the accumulator is
  // mis-wired, and the write-versus-flush SPLIT — the figure this bead
  // exists to publish — is taken straight from those legs.
  if (!s.identityOk) {
    say(
      'VERDICT: FAILED — write + gap + force does not equal the published total on every arm.',
      '         The leg accounting is mis-wired and the write/flush split cannot be quoted.'
    );
  }
  const unsettled = s.warmupUnsettled || [];
  if (unsettled.length) {
    say(
      `VERDICT: REFUSED — warm-up never settled inside the ${s.warmupMax}-window ceiling on:`,
      `         ${unsettled.join(', ')}. Those arms were measured on a site that was still`,
      '         trending, so their figures are of the trajectory, not of the arm. Raise',
      '         HN_WARMUP_MAX and re-run (rf2-rr6do).'
    );
  }
  const clamped = s.clamped || [];
  if (clamped.length) {
    say(
      'VERDICT: REFUSED — a quoted leg sits on the clock quantum, so the absolute table above',
      `         is not a reading of the arm: ${clamped.join(', ')}. Raise HN_WRITES so each`,
      '         sample carries at least 10x the quantum; do not loosen the multiple (rf2-rr6do).'
    );
  }

  if (lines.length === 0) {
    say('VERDICT: reportable.');
    return { code: 0, lines };
  }
  const code = pageErrors.length || s.positionsLost
    ? 1
    : s.orderRefuse
      ? 2
      : s.leaked || s.badTotal > 0 || !s.identityOk
        ? 1
        : unsettled.length
          ? 3
          : 4;
  return { code, lines };
}

// The donor's shared navigation, with its ceiling NAMED. Reached by path
// rather than by package because it lives in a test tree this bead does not
// own; if it ever moves, the fallback below keeps this driver honest rather
// than silently taking Playwright's anonymous 30s default.
function loadNavigate() {
  try {
    return require(path.join(
      IMPL,
      'freehand/test/re_frame/freehand/bench/navigate.cjs'
    ));
  } catch (_) {
    const NAV_TIMEOUT_MS = 60 * 1000;
    return {
      NAV_TIMEOUT_MS,
      navigate: async (page, url, { timeoutMs, waitUntil, budget }) => {
        try {
          await page.goto(url, { waitUntil, timeout: timeoutMs });
        } catch (err) {
          throw new Error(
            `NAVIGATION FAILED — this is the page.goto ceiling (waitUntil '${waitUntil}', ` +
              `timeout ${timeoutMs}ms), NOT ${budget}, which had not yet started. ` +
              `Underlying: ${err.message}`
          );
        }
      },
    };
  }
}

module.exports = { verdict };

// Requiring this file must NOT drive it — the exit-path test loads `verdict`
// out of it, and a driver that launches Chromium on `require` could not be
// tested at all.
if (require.main === module) {
  main().catch((e) => {
    console.error('[hn] FAILED —', e && e.stack ? e.stack : e);
    process.exit(1);
  });
}
