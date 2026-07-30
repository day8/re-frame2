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
// bead. `HN_BASE_BUILD` selects the template; point it at the Hicasso lane
// build the moment rf2-2rtt6.2 lands one, with no change to this file.
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
//      EMPTY `flushSync` for its drain, which must go red.
//
// EXIT CODES
//   0  reportable
//   1  a gate failed, or the run could not complete
//   2  THE ARM-ORDER GUARD REFUSED. No figure from this run may be
//      published. Repair the arm — more warm-up, more rounds, a quieter
//      box — never the guard's tolerance.

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

// --- the plan ---------------------------------------------------------------
//
// Four rounds of six measured samples is 24 samples an arm, which is what
// the guard's phase rule needs: it splits an arm's trajectory into THIRDS
// and compares the first against the last, and a third has to carry a
// RANGE for the house rule to apply. At six samples a third is one sample
// and the question collapses to a bare ratio.
const ROUNDS = Number(process.env.HN_ROUNDS || 4);
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
const WARMUP = Number(process.env.HN_WARMUP || 6);
const WARMUP_MAX = Number(process.env.HN_WARMUP_MAX || 14);

// The guard's tolerance, and the donor's. It is NOT a knob for making a
// refusal go away: a refusal means the figure moved with the plan, and the
// repair is the arm.
const TOLERANCE = Number(process.env.HN_TOLERANCE || 0.10);

// The positive control's two rungs, in milliseconds of predicted burn per
// write. Both comfortably clear of the 100 us clamp; the difference is
// what cancels the clock-read overhead the direct reading contains.
const CTL_1 = Number(process.env.HN_CTL_1 || 0.3);
const CTL_2 = Number(process.env.HN_CTL_2 || 0.9);

const BASE_BUILD = process.env.HN_BASE_BUILD || 'freehand-release';
const NO_BUILD = process.argv.includes('--no-build');

const CONFIG_MERGE =
  '{:output-dir "out/hicasso-narrow" :asset-path "." ' +
  ':modules {:main {:init-fn re-frame.bench.hicasso-narrow-app/-main}}}';

function build() {
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
  page.on('pageerror', (e) => console.error('[hn] page error:', e.message));

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
  console.error(`[hn] arms: ${arms.join(', ')}  (${cellsN} cells, ${WRITES} writes/sample)`);

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
  for (const arm of arms) {
    const xs = [];
    for (let i = 0; i < WARMUP_MAX; i++) {
      xs.push(await page.evaluate(([a, w]) => window.HN.warm(a, w), [arm, WRITES]));
      if (i + 1 >= WARMUP) {
        const t = Math.max(1, Math.floor(xs.length / 3));
        const first = summarise(xs.slice(0, t));
        const last = summarise(xs.slice(-t));
        const ratio = last.p50 === 0 ? 1 : first.p50 / last.p50;
        // Settled = the first third and the last third are no longer
        // separated. Both halves of the house rule: the medians must be
        // within tolerance OR the ranges must overlap.
        if (Math.abs(ratio - 1) <= TOLERANCE || !disjoint(first, last)) break;
      }
    }
    trajectories[arm] = xs;
    console.error(`[hn] warm ${arm.padEnd(16)} ${xs.map((x) => num(x, 3)).join(' ')}`);
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
  say(`sample           ${WRITES} narrow writes; per-write = sample / ${WRITES}`);
  say(`plan             ${ROUNDS} rounds x (${IN_ROUND_WARMUP} in-round warmup + ${SAMPLES} samples), arms interleaved`);
  say(`clock quantum    ${num(quantum, 5)} ms (measured in-page)`);
  say(`window           t0 -> control -> WRITE -> microtask -> FLUSH -> t3 -> DOM read-back`);
  say(`published figure t3 - t_control  =  write + gap + force`);
  say('');

  // --- the headline table -------------------------------------------------
  say('PER-WRITE MILLISECONDS — p50 [min-max] across all measured samples');
  say('');
  say(
    '  arm               write+flush            write                  flush                  write%'
  );
  const headline = {};
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const tot = legOf(rows, 'total');
    const wr = legOf(rows, 'write');
    const fo = legOf(rows, 'force');
    const gp = legOf(rows, 'gap');
    headline[arm] = { total: tot, write: wr, force: fo, gap: gp };
    const share = tot && tot.p50 > 0 ? ((wr.p50 / tot.p50) * 100).toFixed(0) : '--';
    say(`  ${arm.padEnd(17)} ${rng(tot).padEnd(22)} ${rng(wr).padEnd(22)} ${rng(fo).padEnd(22)} ${share}%`);
  }
  say('');
  say('  (the microtask gap is priced separately and is expected to be ~0 on every arm here:');
  say('   a reagent.core/flush is already on React\'s sync lane, so no arm needs the boundary.)');
  for (const arm of arms) {
    say(`     gap  ${arm.padEnd(17)} ${rng(headline[arm].gap)}`);
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
  let badTotal = 0;
  let writeTotal = 0;
  for (const arm of arms) {
    const rows = byArm(pass1, arm);
    const bad = rows.reduce((a, s) => a + s.bad, 0);
    const n = rows.length * WRITES;
    badTotal += bad;
    writeTotal += n;
    const note = arm === 'instrument' ? '  (pseudo-arm: renders no cell, verification skipped)' : '';
    say(`  ${arm.padEnd(17)} ${bad} unverified of ${n}${note}`);
  }
  say(`  ${'ALL ARMS'.padEnd(17)} ${badTotal} unverified of ${writeTotal}`);
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
    plan: { rounds: ROUNDS, inRoundWarmup: IN_ROUND_WARMUP, samples: SAMPLES, writes: WRITES },
    warmup: trajectories,
    control: { rung1: CTL_1, rung2: CTL_2, measured1: c1, measured2: c2 },
    negativeControl: neg,
    headline,
    unverified: { bad: badTotal, of: writeTotal },
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
  if (report.refuse) {
    say('VERDICT: REFUSED by the arm-order guard. No figure above may be published.');
    say('         Repair the arm — more warm-up, more rounds, a quieter box. Not the tolerance.');
    process.exitCode = 2;
    return;
  }
  if (leaked) {
    say('VERDICT: FAILED — an arm\'s total moved with the control size. The leg accounting leaks.');
    process.exitCode = 1;
    return;
  }
  say('VERDICT: reportable.');
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

main().catch((e) => {
  console.error('[hn] FAILED —', e && e.stack ? e.stack : e);
  process.exit(1);
});
