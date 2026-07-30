#!/usr/bin/env node
// HD-008's driver — build once, serve once, run three adapters, refuse a
// contaminated figure (rf2-2rtt6.7).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs
//
// ## What this measures, and why it comes before any API
//
// HD-008 is EP-0038's STOP-GATE. Before a line of Hicasso's API is
// designed, the central hypothesis is assembled out of parts already in
// this repository — reagent-slim's `:f>` function-component path and its
// runtime hiccup interpreter, plus the existing UIx `use-subscribe`
// spine — and measured against both Reagent paths and against direct UIx.
// Two rungs: markup+reactivity, then plus the product shell (one
// frame-context hook, native event-vector lowering). If the composed arm
// cannot clearly beat both Reagent paths and stay acceptably close to
// UIx, the programme stops and adapters-plus-sugar is the recorded
// SUCCESSFUL outcome. A "no" here costs a fraction of building the thing.
//
// ## THE RULING IS NOT THIS SCRIPT'S TO ISSUE
//
// Per HD-013 and HD-014 the stop/continue ruling is a DELEGATED ADVISORY
// ruling issued ONLY against the PUBLISHED P0 baseline table, recorded on
// the standard bead rf2-2rtt6.1, and operator-overturnable. This script
// prints measurements. It prints no verdict, and it must never learn to.
//
// ## The build id
//
// No new build id, and `implementation/shadow-cljs.edn` is not touched:
// rf2-2rtt6.2 owns the measurement lane and any build-id addition (a
// hot-zone, sequenced file). This rides its `:hicasso-bench` with an
// output directory and an `:init-fn` merged in at the CLI, which is the
// seam rf2-2rtt6.2's own driver established for exactly this.
//
// ## Exit codes
//
//   0  measured, and no figure moves with its position in the plan
//   1  the run failed its own gates (parity, lowering, page error)
//   2  THE ARM-ORDER GUARD REFUSED — repair the arm, never the guard
//
// Exit 2 is deliberately distinct from exit 1. A refusal is not a broken
// script: it is the instrument saying that a figure it produced depends on
// where in the plan it was measured, and that such a figure may not be
// reported. `rf2-jr76s` published `16.1052` and `8.0027` for the SAME
// control — a plausible, precise, wrong number — and was caught only
// because both orders were run and disagreed with each other.

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

// Both shared with the freehand bench tree, and reached the same way
// rf2-2rtt6.2's own driver reaches `navigate`: one navigation helper and one
// arm-order guard for the repository, never a second copy per lane.
const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
const guard = require('../../freehand/bench/order_guard.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const REPO = path.resolve(IMPL, '..');
// rf2-2rtt6.2's lane, reused rather than re-minted: ONE build id serves the
// whole programme, and HD-017 makes a new one a hot-zone edit of
// implementation/shadow-cljs.edn that rf2-2rtt6.2 owns. `:hicasso-bench` is
// already `:advanced` with goog.DEBUG false, which is what HD-012 requires of
// every bar-relevant figure, so this arm needs nothing of its own.
const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.HD8_OUT_DIR || 'out/hd8-donor';
const INIT_FN = 're-frame.bench.hicasso.hd8-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.HD8_PORT || 8129);

// The three runs. One adapter per process is Spec 006's rule, and the two
// Reagent paths need the ratom spine while the frontier and donor arms
// ride React hooks — so the arms that can be compared head to head are
// partitioned this way and every HD-008 comparison lands WITHIN a run.
const RUNS = [
  { id: 'uix', query: '?adapter=uix', why: 'donor rungs against the frontier' },
  { id: 'reagent', query: '?adapter=reagent', why: 'donor rungs against stock Reagent' },
  { id: 'slim', query: '?adapter=slim', why: 'donor rungs against reagent-slim' },
];

// The guard's tolerance. `order_guard.cjs` defends the choice: 0.10 sits far
// below the 2.01x the recorded fault produced and far above the 0.4% the same
// study's uncontaminated arms reproduced to. A browser mount clock is noisier
// than a node allocation counter, so the phase factor is given room — but the
// number is NAMED here rather than defaulted, because an unnamed tolerance is
// the anonymous-ceiling defect wearing a different hat.
const TOLERANCE = Number(process.env.HD8_TOLERANCE || 0.35);

// ---------------------------------------------------------------------------
// Build + serve
// ---------------------------------------------------------------------------

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline and reports `EOF while reading`
// from a fragment.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function sh(cmd, args, opts = {}) {
  return spawnSync(cmd, args, { cwd: REPO, encoding: 'utf8', ...opts });
}

function revision() {
  const r = sh('git', ['rev-parse', 'HEAD']);
  return r.status === 0 ? r.stdout.trim() : 'unknown';
}

function build() {
  console.error('[hd8] building :advanced bundle (goog.DEBUG false) ...');
  // `node cli/runner.js` rather than the `.cmd` shim: spawning a shim on
  // Windows needs `shell: true`, and a shell concatenates argv, which is the
  // other way the config-merge EDN gets torn in half.
  const runner = path.join(IMPL, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const r = spawnSync(
    process.execPath,
    [runner, 'release', BUILD_ID, '--config-merge', CONFIG_MERGE],
    { cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] }
  );
  if (r.status !== 0) {
    console.error(`[hd8] build failed with status ${r.status}`);
    process.exit(1);
  }
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>HD-008</title></head>' +
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
// One run
// ---------------------------------------------------------------------------

async function runOne(chromium, run) {
  // A FRESH browser per run, not merely a fresh page. `rf/init!` installs
  // exactly one adapter per process (Spec 006) and the CLJS runtime holds
  // registry state at module scope; reusing a context would carry the
  // previous adapter's installation into the next run's measurement.
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const lines = [];
  page.on('console', (m) => {
    const t = m.text();
    if (t.startsWith(';; HD8')) lines.push(t);
  });
  page.on('pageerror', (e) => console.error(`[hd8:${run.id}] page error:`, e.message));
  // `'commit'`, not `'load'` (rf2-p9fa3). `hd8-app/-main` is this bundle's
  // `:init-fn`, so the parity pass and every mount row run INSIDE the
  // `<script>` — `load` cannot fire until the benchmark has yielded, which
  // is exactly what the sentinel below waits for against a budget twenty
  // times larger.
  await navigate(page, `http://127.0.0.1:${PORT}/${run.query}`, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: 'the 20-minute wait for `window.HD8_DONE`',
  });
  await page.waitForFunction('window.HD8_DONE === true || window.HD8_ERROR', null, {
    timeout: 20 * 60 * 1000,
  });
  const err = await page.evaluate('window.HD8_ERROR || null');
  const results = await page.evaluate('window.HD8_RESULTS || {}');
  const samples = await page.evaluate('window.HD8_SAMPLES || []');
  const summary = await page.evaluate('window.HD8_SUMMARY || {}');
  const userAgent = await page.evaluate('navigator.userAgent');
  await browser.close();
  return { id: run.id, why: run.why, err, results, samples, summary, userAgent, lines };
}

// ---------------------------------------------------------------------------
// The guard, applied to what was actually measured
// ---------------------------------------------------------------------------

function adjudicate(run) {
  const verdicts = [];
  let refused = false;
  for (const row of run.samples) {
    const v = guard.verdict(row.samples, { tolerance: TOLERANCE });
    verdicts.push({ row: row.row, verdict: v });
    if (v.refuse) refused = true;
  }
  return { verdicts, refused };
}

// ---------------------------------------------------------------------------
// The table
// ---------------------------------------------------------------------------

const band = (v) =>
  v.unpublished
    ? `UNPUBLISHED (${v.unverified}/${v.of} unverified)`
    : `${v.min.toFixed(3)} – ${v.max.toFixed(3)}${v.straddles1 ? '  [STRADDLES 1.0 — indistinguishable]' : ''}`;

// The cross-run table. Every arm's figure is a ratio to the floor measured
// in its OWN round, and the floor is the same hand-written `createElement`
// code in the same bundle, touching no adapter at all. That is what makes
// the columns comparable across runs — and it is a WEAKER warrant than the
// within-run head-to-head ratios printed beside it, so the two are never
// mixed in one number.
function crossRun(runs) {
  const rows = new Set();
  for (const r of runs) for (const k of Object.keys(r.summary)) rows.add(k);
  const out = [];
  out.push('');
  out.push(';; ==== HD8 CROSS-RUN TABLE — every figure a RANGE over 6 rounds, ratio to the ====');
  out.push(';; ==== floor measured in the SAME round. The floor is identical code in an   ====');
  out.push(';; ==== identical bundle; only the installed adapter differs between runs.    ====');
  for (const row of rows) {
    out.push(`;;`);
    out.push(`;;   ${row}`);
    for (const run of runs) {
      const s = run.summary[row];
      if (!s) continue;
      for (const [arm, v] of Object.entries(s.vsFloor)) {
        if (arm === 'floor') continue;
        out.push(`;;     [${run.id.padEnd(7)}] ${arm.padEnd(14)} vs floor   ${band(v)}`);
      }
      for (const [pair, v] of Object.entries(s.headToHead)) {
        out.push(`;;     [${run.id.padEnd(7)}] ${pair.padEnd(26)}  ${band(v)}`);
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

(async () => {
  // The guard's own self-test FIRST. A guard nobody has watched catch its
  // recorded faults is not a guard, and running it after the measurement
  // would mean discovering a broken instrument on the far side of an hour.
  const st = guard.selfTest();
  console.log(';; ==== HD8 ARM-ORDER GUARD SELF-TEST ====');
  for (const c of st.checks) {
    console.log(`;;   ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.detail ? '  — ' + c.detail : ''}`);
  }
  if (!st.ok) {
    console.error('[hd8] the arm-order guard failed its own self-test; nothing was measured');
    process.exit(1);
  }

  const sha = revision();
  console.log(`;; ==== HD8 PROVENANCE ====`);
  console.log(`;;   bead        rf2-2rtt6.7 (HD-008, EP-0038)`);
  console.log(`;;   commit      ${sha}`);
  console.log(`;;   reproduce   node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs`);
  console.log(`;;   build       shadow-cljs release freehand-release (:advanced, goog.DEBUG false)`);
  console.log(`;;   node        ${process.version}`);

  build();
  const server = serve();
  const runs = [];
  let hardFail = null;
  try {
    const { chromium } = require('playwright');
    for (const r of RUNS) {
      console.error(`[hd8] run ${r.id} — ${r.why}`);
      const out = await runOne(chromium, r);
      runs.push(out);
      if (out.err) hardFail = `${r.id}: ${out.err}`;
    }
  } finally {
    server.close();
  }

  let refused = false;
  for (const run of runs) {
    console.log(`\n;; ==== HD8 RUN ${run.id} — ${run.why} ====`);
    console.log(`;;   runtime  ${run.userAgent}`);
    for (const line of run.lines) console.log(line);
    const { verdicts, refused: r } = adjudicate(run);
    refused = refused || r;
    for (const { row, verdict } of verdicts) {
      for (const line of guard.format(verdict, `${run.id} / ${row}`)) console.log(line);
    }
  }

  for (const line of crossRun(runs)) console.log(line);

  console.log('');
  console.log(';; ==== HD8 — THE RULING IS NOT THIS INSTRUMENT\'S TO ISSUE ====');
  console.log(';;   HD-008\'s stop/continue ruling is a DELEGATED ADVISORY ruling (HD-013),');
  console.log(';;   issued ONLY against the PUBLISHED P0 baseline table, recorded on the');
  console.log(';;   standard bead rf2-2rtt6.1, and operator-overturnable. The P0 table is');
  console.log(';;   being filled by rf2-2rtt6.2/.3/.4/.5 and is not published yet, and the');
  console.log(';;   red-zone thresholds (= the measured UIx ratios, per witness family, on');
  console.log(';;   clock and retained heap) do not exist yet either. These are');
  console.log(';;   measurements. There is no verdict here and there must not be.');

  if (hardFail) {
    console.error(`[hd8] FAILED: ${hardFail}`);
    process.exit(1);
  }
  if (refused) {
    // Exit 2, and the message says what to do with it. A guard that refused
    // is reporting a defect in the ARM, not in itself: the remedy is to make
    // the arm's figure independent of where it was measured, never to widen
    // the tolerance until the refusal stops.
    console.error(
      '[hd8] ARM-ORDER GUARD REFUSED — at least one figure above depends on where in the ' +
        'plan it was measured, and may not be reported as measured. Repair the arm, not the ' +
        'guard (rf2-88pie).'
    );
    process.exit(2);
  }
  console.error('[hd8] ok — measured, and no arm reads differently for its position in the plan');
})();
