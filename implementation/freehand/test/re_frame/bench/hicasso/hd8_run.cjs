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
//   3  a write arm's value never reached the DOM (failed read-back)
//   4  a yield correction was REFUSED and the row's figure is unadjusted
//
// Exit 2 is deliberately distinct from exit 1. A refusal is not a broken
// script: it is the instrument saying that a figure it produced depends on
// where in the plan it was measured, and that such a figure may not be
// reported. `rf2-jr76s` published `16.1052` and `8.0027` for the SAME
// control — a plausible, precise, wrong number — and was caught only
// because both orders were run and disagreed with each other.
//
// 3 and 4 are rf2-x6g04's repair, and they number as `hd8_clock_run.cjs`
// already numbered the first of them (its exit 3 is the same read-back
// refusal). Both conditions were COMPUTED AND PRINTED by this driver and
// neither reached the exit code: it read `hardFail`, `contractFailed` and
// the arm-order `refused`, then announced `[hd8] ok`. A run could print
//
//     ;;     [slim   ] reagent-slim   vs floor   UNPUBLISHED (1/78 unverified)
//     ;;     [slim   ] YIELD CORRECTION: REFUSED (correction-changes-the-verdict)
//
// and exit 0 on both. The figure IS suppressed in the table either way — the
// marker replaces it and the head-to-head pairs touching the arm are dropped
// upstream — so no reader could copy a number out. What was missing is the
// PROCESS's own statement, and a green exit is what a future reader banks.
// See the note above `verdict` for the shape and for what did not change.

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
const { watchPage } = require('../../freehand/bench/sentinel.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
// This driver is where the fault was found: run the P0 lane, then run HD-008,
// and the page died before taking a sample.
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

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
const ALL_RUNS = [
  { id: 'uix', query: '?adapter=uix', why: 'donor rungs against the frontier' },
  { id: 'reagent', query: '?adapter=reagent', why: 'donor rungs against stock Reagent' },
  { id: 'slim', query: '?adapter=slim', why: 'donor rungs against reagent-slim' },
];

// `HD8_ONLY=slim` drives one run instead of three, over the SAME bundle and
// the same gates. Not a shortcut: re-taking one run's suppressed row must not
// mint a competing set of figures for the rows already published at another
// SHA, and a driver that could only run all three would force exactly that.
// Unset is the full three, so the published shape is the default.
const ONLY = (process.env.HD8_ONLY || '').trim();
const RUNS = ONLY ? ALL_RUNS.filter((r) => ONLY.split(',').includes(r.id)) : ALL_RUNS;
if (RUNS.length === 0) {
  console.error(`[hd8] HD8_ONLY=${ONLY} selects no run; known ids: ${ALL_RUNS.map((r) => r.id).join(', ')}`);
  process.exit(1);
}

// ...AND `HD8_ONLY` ALONE DOES NOT DELIVER WHAT THE PARAGRAPH ABOVE PROMISES.
//
// It selects ADAPTERS. Every selected run still executes the bundle's whole
// row set, so `HD8_ONLY=slim` emits `mount-M`, `mount-U`, `write-narrow` and
// `write-bulk` — four rows, of which a re-take needed one or two. Nothing
// mechanically stopped the other three being read as figures beside the rows
// published from the full three-run sweep, which is exactly the competing set
// the comment said it prevented (rf2-b69lw, from the PR #7269 audit).
//
// So the declaration is made EXPLICIT and the default is the safe one:
//
//   HD8_ONLY unset          the full three-run sweep — the published shape;
//                           every row publishes.
//   HD8_ONLY set, no ROWS   a PARTIAL run. The driver cannot know which
//                           re-take was intended, so NO row publishes and
//                           every one is stamped NON-PUBLISHING.
//   HD8_ONLY + HD8_ROWS     the named rows publish; every other row the run
//                           emits is stamped NON-PUBLISHING.
//
// Marked rather than suppressed: the gates that qualify a row (parity, the
// positive control, the lowering check, the read-back, the arm-order guard)
// run over the whole set either way, and a row withheld from the log is a row
// nobody can diagnose. What a partial run must never do is emit a figure that
// LOOKS like the published one.
const KNOWN_ROWS = ['mount-M', 'mount-U', 'write-narrow', 'write-bulk'];
const ROWS = (process.env.HD8_ROWS || '').trim();
const DECLARED_ROWS = ROWS
  ? new Set(
      ROWS.split(',')
        .map((s) => s.trim())
        .filter(Boolean)
    )
  : null;
if (DECLARED_ROWS) {
  const unknown = [...DECLARED_ROWS].filter((r) => !KNOWN_ROWS.includes(r));
  if (unknown.length) {
    console.error(
      `[hd8] HD8_ROWS names ${unknown.join(', ')}, which this instrument does not emit; ` +
        `known rows: ${KNOWN_ROWS.join(', ')}. A typo here would silently mark every row ` +
        `non-publishing, so it is an error instead.`
    );
    process.exit(1);
  }
  if (!ONLY) {
    console.error(
      '[hd8] HD8_ROWS is set without HD8_ONLY. A full sweep IS the published shape and ' +
        'publishes every row; narrowing what publishes without narrowing what ran would ' +
        'describe a run that did not happen.'
    );
    process.exit(1);
  }
}
const PARTIAL = Boolean(ONLY);
// A row publishes only from the full sweep, or when a partial run NAMED it.
const publishing = (row) => (!PARTIAL ? true : DECLARED_ROWS ? DECLARED_ROWS.has(row) : false);

// The guard's tolerance. `order_guard.cjs` defends the choice: 0.10 sits far
// below the 2.01x the recorded fault produced and far above the 0.4% the same
// study's uncontaminated arms reproduced to. A browser mount clock is noisier
// than a node allocation counter, so the phase factor is given room — but the
// number is NAMED here rather than defaulted, because an unnamed tolerance is
// the anonymous-ceiling defect wearing a different hat.
const TOLERANCE = Number(process.env.HD8_TOLERANCE || 0.35);

// The page's own budget, for the case where the page is ALIVE and simply has
// not finished. Six rounds of every witness across three adapters is minutes,
// not seconds. It is no longer the budget a page ERROR is reported against —
// `sentinel.cjs` races this against the page dying, so a throw is reported in
// the second it happens instead of twenty minutes later (rf2-f5roa).
const SENTINEL_TIMEOUT_MS = 20 * 60 * 1000;

// `--selftest` runs every adjudicator's fixtures and exits, before anything is
// built or launched. The guard's and the table's already ran at the head of
// the sweep; this makes them — and the EXIT DECISION's, which is new — usable
// on their own, so an operator can see the instrument refuse in a second
// rather than on the far side of an hour (the shape `clock_run.cjs` uses).
const SELFTEST_ONLY = process.argv.includes('--selftest');

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
  // The lane's cache rule, before anything reads the cache. `lane_cache.cjs`
  // carries the measurement and the rejected alternatives.
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[hd8] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error('[hd8] building :advanced bundle (goog.DEBUG false) ...');
  // The hardened spawn form (shadow's own `cli/runner.js`, never the `.cmd`
  // shim — a shim needs `shell: true`, and a shell concatenates argv, which is
  // the other way the config-merge EDN gets torn in half) lives in
  // `lane_build.cjs` now, together with the warning verdict.
  shadowBuild({
    impl: IMPL,
    mode: 'release',
    buildId: BUILD_ID,
    configMerge: CONFIG_MERGE,
    tag: 'hd8',
  });
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
  try {
    const page = await browser.newPage();
    const lines = [];
    page.on('console', (m) => {
      const t = m.text();
      if (t.startsWith(';; HD8')) lines.push(t);
    });
    // Watching starts BEFORE the navigation, because the fault this catches
    // most often — a contaminated Shadow cache throwing out of ReactDOM —
    // happens during bundle execution, which is inside the navigation. The
    // driver used to merely log it here and then wait the full twenty minutes
    // for a sentinel the throw had already made unreachable (rf2-f5roa).
    const watch = watchPage(page, `hd8:${run.id}`);
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
    await watch.race('window.HD8_DONE === true || window.HD8_ERROR', {
      timeoutMs: SENTINEL_TIMEOUT_MS,
      budget: 'the 20-minute wait for `window.HD8_DONE`',
    });
    const err = await page.evaluate('window.HD8_ERROR || null');
    const results = await page.evaluate('window.HD8_RESULTS || {}');
    const samples = await page.evaluate('window.HD8_SAMPLES || []');
    const summary = await page.evaluate('window.HD8_SUMMARY || {}');
    // The harness-microtask correction's VERDICT per write row — and, on a
    // `:corrected` row, BOTH of its bands (rf2-b69lw). On a JS-readable
    // channel rather than only inside the EDN record, because the cross-run
    // table is what a reader copies a figure out of and a corrected row that
    // appears there looking uncorrected — or whose corrected endpoints the
    // table cannot print — is the fault the contract exists to prevent.
    const correction = await page.evaluate('window.HD8_CORRECTION || {}');
    // The contract's own self-test result, on a JS-readable channel so the
    // driver can FAIL on it rather than leaving it in an EDN blob nobody
    // parses — which is the exact fault the contract itself was filed for.
    const contractSelfTest = await page.evaluate(
      'window.HD8_CORRECTION_SELFTEST === undefined ? null : window.HD8_CORRECTION_SELFTEST'
    );
    const userAgent = await page.evaluate('navigator.userAgent');
    // THE SENTINEL'S OWN FAILURE LIST, read rather than discarded (rf2-x6g04).
    // `watch.race` throws on a failure that arrives while it is waiting, so
    // reaching this line means any failure arrived in the SAME TICK as the
    // sentinel or after it — which the race cannot order and therefore cannot
    // refuse. Every sibling that installs this watcher carries the same
    // backstop (`coldmount_run.cjs` ~193, `p0_converge_run.cjs` ~236,
    // `ime_run.cjs`, `chrome_run.cjs`, `adoption_witness_run.cjs`); this
    // driver installed the watcher and then read nobody's failures at all, so
    // a `pageerror` landing beside `HD8_DONE` was recorded, printed by
    // `sentinel.cjs` itself, and consulted by nothing.
    const pageErrors = watch.failures.map((f) => `${f.kind}: ${f.detail}`);
    watch.dispose();
    return {
      id: run.id, why: run.why, err, results, samples, summary,
      correction, contractSelfTest, userAgent, lines, pageErrors,
    };
  } finally {
    await browser.close();
  }
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
  if (PARTIAL) {
    out.push(';;');
    out.push(`;;   PARTIAL RUN — HD8_ONLY=${ONLY}. This is not the published shape.`);
    if (DECLARED_ROWS) {
      out.push(`;;   Publishing: ${[...DECLARED_ROWS].join(', ')}. Every other row below is a`);
      out.push(';;   by-product of the adapter selection and is marked NON-PUBLISHING.');
    } else {
      out.push(';;   HD8_ROWS was not set, so NO row below publishes. Re-run with HD8_ROWS');
      out.push(';;   naming the row this re-take is for.');
    }
  }
  for (const row of rows) {
    const pub = publishing(row);
    out.push(`;;`);
    // The stamp goes on the row HEADING, so a reader who copies a figure out of
    // the table cannot get it without the label that says it may not be quoted.
    out.push(`;;   ${row}${pub ? '' : '   *** NON-PUBLISHING — a partial run\'s by-product ***'}`);
    for (const run of runs) {
      const s = run.summary[row];
      if (!s) continue;
      const mark = pub ? '' : ' [NON-PUBLISHING]';
      // The harness-microtask correction, stamped on the figures it governs.
      // `:not-owed` is the common case and says nothing; the other three all
      // change what a reader may do with the numbers on the line below.
      const c = (run.correction || {})[row];
      if (c && c.verdict !== 'not-owed') {
        out.push(
          `;;     [${run.id.padEnd(7)}] YIELD CORRECTION: ${c.verdict.toUpperCase()}` +
            `${c.reason ? ` (${c.reason})` : ''}${c.bound != null ? ` — bound ${c.bound} ms` : ''}`
        );
        out.push(`;;                 ${c.why}`);
      }
      // A `:corrected` verdict publishes BOTH bands, so BOTH must be in the
      // table a reader copies from. The EDN record carried the corrected
      // endpoints while this table printed only the unadjusted ones — a
      // corrected row whose correction a reader cannot copy (rf2-b69lw,
      // from the PR #7282 audit). Each line carries its own label, so a
      // figure cannot leave the table without the name of its band.
      const cSummary = c && c.verdict === 'corrected' ? c.summaryCorrected || {} : {};
      const cH2h = c && c.verdict === 'corrected' ? c.headToHeadCorrected || {} : {};
      // AN UNPUBLISHED ORIGINAL HAS NO CORRECTED BAND, and the table holds
      // that line itself rather than trusting the export: the correction
      // rebuilds its bands from per-round timings that are retained even for
      // an arm whose writes failed their DOM read-back, and this table
      // printed `1.200 – 1.300 [CORRECTED]` directly beneath `UNPUBLISHED
      // (1/78 unverified)` for the SAME arm (rf2-b69lw, from the PR #7295
      // audit). A failed read-back has NO publishable timing — corrected or
      // not — so the original's publication mask decides both lines. A
      // marker arriving in the corrected band itself is refused the same
      // way: `band(marker)` would print UNPUBLISHED twice, and twice is not
      // a figure either.
      const correctedBand = (v, cv) => (v.unpublished || !cv || cv.unpublished ? null : cv);
      for (const [arm, v] of Object.entries(s.vsFloor)) {
        if (arm === 'floor') continue;
        const cv = correctedBand(v, cSummary[arm]);
        out.push(
          `;;     [${run.id.padEnd(7)}] ${arm.padEnd(14)} vs floor   ${band(v)}${cv ? '  [UNADJUSTED]' : ''}${mark}`
        );
        if (cv) {
          out.push(`;;     [${run.id.padEnd(7)}] ${arm.padEnd(14)} vs floor   ${band(cv)}  [CORRECTED]${mark}`);
        }
      }
      for (const [pair, v] of Object.entries(s.headToHead)) {
        const cv = correctedBand(v, cH2h[pair]);
        out.push(
          `;;     [${run.id.padEnd(7)}] ${pair.padEnd(26)}  ${band(v)}${cv ? '  [UNADJUSTED]' : ''}${mark}`
        );
        if (cv) {
          out.push(`;;     [${run.id.padEnd(7)}] ${pair.padEnd(26)}  ${band(cv)}  [CORRECTED]${mark}`);
        }
      }
    }
  }
  return out;
}

// The corrected-table fixture (rf2-b69lw, from the PR #7295 audit) — the
// EXACT REACHABLE SHAPE, replayed through the live `crossRun` before
// anything is measured, the same argument as `guard.selfTest()`: a refusal
// nobody has watched fire is not a refusal, and this polarity fires only
// when a run both fails a read-back AND resolves a yield correction, which
// no live run can be relied on to do. The fixture's export deliberately
// carries a numeric corrected band for the unpublished arm — the very
// shape the pre-repair pipeline produced — so the check pins the TABLE's
// own refusal, independent of the CLJS-side mask upstream of it.
function tableSelfTest() {
  const runs = [
    {
      id: 'slim',
      summary: {
        'write-narrow': {
          vsFloor: {
            floor: { min: 1.0, max: 1.0, straddles1: true },
            'reagent-slim': { unpublished: 'failed-dom-read-back', unverified: 1, of: 78 },
            'donor-r1': { min: 2.0, max: 2.0, straddles1: false },
          },
          headToHead: {},
        },
      },
      correction: {
        'write-narrow': {
          verdict: 'corrected',
          reason: null,
          bound: 0.1,
          why: 'fixture — the PR #7295 audit shape',
          summaryCorrected: {
            'reagent-slim': { min: 1.2, max: 1.3, straddles1: false },
            'donor-r1': { min: 2.2, max: 2.3, straddles1: false },
          },
          headToHeadCorrected: {},
        },
      },
    },
  ];
  const out = crossRun(runs).join('\n');
  const checks = [
    {
      name: 'an UNPUBLISHED original grows neither a [CORRECTED] band nor an [UNADJUSTED] label',
      ok: !/reagent-slim.*\[CORRECTED\]/.test(out) && !/reagent-slim.*\[UNADJUSTED\]/.test(out),
    },
    {
      name: 'the UNPUBLISHED marker itself still prints, with its counts',
      ok: /reagent-slim\s+vs floor\s+UNPUBLISHED \(1\/78 unverified\)/.test(out),
    },
    {
      name: 'a published arm still prints BOTH labelled bands',
      ok: /donor-r1.*2\.000 – 2\.000.*\[UNADJUSTED\]/.test(out) && /donor-r1.*2\.200 – 2\.300.*\[CORRECTED\]/.test(out),
    },
  ];
  return { ok: checks.every((c) => c.ok), checks };
}

// ---------------------------------------------------------------------------
// The exit decision
// ---------------------------------------------------------------------------

// THE DRIVER COMPUTED AND PRINTED REFUSALS THAT NEVER REACHED ITS EXIT CODE
// (rf2-x6g04). The old block read exactly three things — `hardFail`,
// `contractFailed` and the arm-order `refused` — and then said `[hd8] ok`.
// Three further conditions were live and unread:
//
//   * A FAILED DOM READ-BACK. `hd8_rows.cljs`'s `mask-failed-read-backs`
//     writes `{:unpublished :failed-dom-read-back ...}` into the row's
//     summary and the table renders `UNPUBLISHED (1/78 unverified)`. It does
//     NOT set `HD8_ERROR` — only `hd8_app.cljs`'s `-main` catch does — so
//     `hardFail` stayed null. Every sibling instrument treats the identical
//     condition as a hard refusal (`hd8_clock_run.cjs` exit 3,
//     `shapes/census_clock_run.cjs` exit 3, `clock_run.cjs` exit 1,
//     `p0_run.cjs`, `b7_run.cjs`, `reads_ladder_run.cjs`,
//     `spine_ablation_run.cjs`).
//
//   * A `:refused` YIELD CORRECTION. The table prints `YIELD CORRECTION:
//     REFUSED (correction-changes-the-verdict)` and the driver then read the
//     verdict ONLY for `'corrected'`. On `refused` the UNADJUSTED number was
//     printed directly beneath a line saying the correction was refused
//     BECAUSE IT CHANGES THE VERDICT — and with no `[UNADJUSTED]` label,
//     since that label is attached to the corrected band that a refusal
//     never produces.
//
//   * A `pageerror` ARRIVING BESIDE THE SENTINEL, recorded by `watchPage`
//     and read by nobody. Handled at its source in `runOne` above.
//
// THE REPAIR IS THE ONE THIS TREE HAS MADE BEFORE (rf2-tb345, rf2-rr6do,
// rf2-y7mw7): the decision moves into ONE pure function over a flat summary,
// so it is checkable without a release build and a headless Chromium. See
// `clock_exit_path.test.cjs`.
//
// NOTHING THAT USED TO REFUSE NOW REFUSES DIFFERENTLY. Precedence preserves
// every code this driver already had — a run that exited 1 still exits 1, a
// run the arm-order guard refused still exits 2 — and the new conditions take
// the codes below them. Each condition is INDEPENDENT: each refuses on its
// own, and when several fire every one of them is named.
//
// No refusal suppresses output. The tables are printed before this is
// consulted, and the marked-not-withheld rule the cross-run table follows is
// unchanged: a refusal is about what may be QUOTED, not about throwing the
// measurement away.

/** The flat record the exit is decided on. One entry per refusable thing. */
function summarise({ hardFail, contractFailed, orderRefused, runs } = {}) {
  const unpublished = [];
  const refusedCorrections = [];
  const pageErrors = [];
  for (const run of runs || []) {
    for (const d of run.pageErrors || []) pageErrors.push({ run: run.id, detail: d });
    for (const [row, s] of Object.entries(run.summary || {})) {
      // Both published surfaces, because both are figures a reader copies:
      // the ratio-to-floor columns and the within-run head-to-head pairs.
      const surfaces = [
        ...Object.entries((s && s.vsFloor) || {}).map(([arm, v]) => [`${arm} vs floor`, v]),
        ...Object.entries((s && s.headToHead) || {}),
      ];
      for (const [figure, v] of surfaces) {
        if (v && v.unpublished) {
          unpublished.push({ run: run.id, row, figure, why: String(v.unpublished), unverified: v.unverified, of: v.of });
        }
      }
    }
    for (const [row, c] of Object.entries(run.correction || {})) {
      if (c && c.verdict === 'refused') {
        refusedCorrections.push({ run: run.id, row, reason: c.reason ? String(c.reason) : 'unstated', why: c.why || '' });
      }
    }
  }
  return {
    hardFail: hardFail || null,
    contractFailed: contractFailed || null,
    orderRefused: Boolean(orderRefused),
    pageErrors,
    unpublished,
    refusedCorrections,
  };
}

/** The run's final decision: `{code, lines}`, and the ONLY seat it has. */
function verdict(summary) {
  const s = summary || {};
  const pageErrors = s.pageErrors || [];
  const unpublished = s.unpublished || [];
  const refusedCorrections = s.refusedCorrections || [];
  const lines = [];

  if (s.hardFail) lines.push(`[hd8] FAILED: ${s.hardFail}`);
  if (pageErrors.length) {
    lines.push(
      `[hd8] FAILED: uncaught page error(s) — every figure above was taken on a page that had ` +
        `already thrown, and a benchmark that threw and kept going publishes a precise number for ` +
        `a page that is not the page under test:\n  ` +
        pageErrors.map((e) => `${e.run}: ${e.detail}`).join('\n  ')
    );
  }
  if (s.contractFailed) {
    lines.push(
      `[hd8] FAILED: ${s.contractFailed}. The gate that decides whether a write row may be ` +
        'published does not agree with its recorded fixtures, so no figure above may be reported.'
    );
  }
  if (s.orderRefused) {
    // Exit 2, and the message says what to do with it. A guard that refused
    // is reporting a defect in the ARM, not in itself: the remedy is to make
    // the arm's figure independent of where it was measured, never to widen
    // the tolerance until the refusal stops.
    lines.push(
      '[hd8] ARM-ORDER GUARD REFUSED — at least one figure above depends on where in the ' +
        'plan it was measured, and may not be reported as measured. Repair the arm, not the ' +
        'guard (rf2-88pie).'
    );
  }
  if (unpublished.length) {
    lines.push(
      '[hd8] REFUSED — a write arm\'s value never reached the DOM (rf2-x6g04). Its clock ' +
        'readings are real milliseconds spent on a page that never changed, which is the ' +
        'cheapest possible way to be fast, so the table above carries UNPUBLISHED in place of a ' +
        'figure and this run may not be reported as a clean measurement of those arms:\n  ' +
        unpublished
          .map((u) => `${u.run} / ${u.row} / ${u.figure}: ${u.why} (${u.unverified} of ${u.of} unverified)`)
          .join('\n  ')
    );
  }
  if (refusedCorrections.length) {
    lines.push(
      '[hd8] REFUSED — the harness-microtask yield correction could not be discharged ' +
        '(rf2-x6g04), so the figure printed for the row above is the UNADJUSTED one and the ' +
        'correction that would have adjusted it was refused:\n  ' +
        refusedCorrections.map((c) => `${c.run} / ${c.row}: ${c.reason}${c.why ? ` — ${c.why}` : ''}`).join('\n  ')
    );
  }

  const code =
    s.hardFail || pageErrors.length || s.contractFailed
      ? 1
      : s.orderRefused
        ? 2
        : unpublished.length
          ? 3
          : refusedCorrections.length
            ? 4
            : 0;
  return { code, lines };
}

/**
 * THE DECISION'S OWN FIXTURES, in the idiom of `guard.selfTest()` and
 * `tableSelfTest()` above: the refusals stated as cases rather than as prose,
 * run by `--selftest` before a browser opens and by `clock_exit_path.test.cjs`
 * in CI.
 *
 * The two cases that matter are the read-back and the refused correction.
 * Both are the shapes this driver actually produces — the first is the very
 * export `tableSelfTest`'s fixture replays — and until rf2-x6g04 both printed
 * their refusal into the table and then exited 0.
 */
function verdictSelfTest() {
  const checks = [];
  const check = (name, ok, detail) => checks.push({ name, ok: !!ok, detail: detail || '' });
  const run = (over) => ({ id: 'slim', summary: {}, correction: {}, pageErrors: [], ...over });
  // The reachable read-back shape, exactly as `mask-failed-read-backs` writes it.
  const readBackRun = () =>
    run({
      summary: {
        'write-narrow': {
          vsFloor: {
            floor: { min: 1.0, max: 1.0, straddles1: true },
            'reagent-slim': { unpublished: 'failed-dom-read-back', unverified: 1, of: 78 },
          },
          headToHead: {},
        },
      },
    });
  const cleanRun = () =>
    run({
      summary: {
        'write-narrow': {
          vsFloor: { floor: { min: 1.0, max: 1.0 }, 'donor-r1': { min: 2.0, max: 2.0 } },
          headToHead: { 'donor-r1 vs reagent': { min: 1.1, max: 1.2 } },
        },
      },
      correction: { 'write-narrow': { verdict: 'not-owed' } },
    });

  const green = verdict(summarise({ runs: [cleanRun()] }));
  check('a run whose every figure published exits 0 and says nothing', green.code === 0 && green.lines.length === 0);

  // --- (a) THE FAILED DOM READ-BACK — the case that used to be green -------
  const rb = verdict(summarise({ runs: [readBackRun()] }));
  check('a failed DOM read-back cannot exit 0', rb.code !== 0, `code ${rb.code}`);
  check('and it exits 3, as hd8_clock_run.cjs already numbered the same refusal', rb.code === 3, `code ${rb.code}`);
  check(
    'and the refusal NAMES the run, the row, the figure and the counts',
    /slim \/ write-narrow \/ reagent-slim vs floor: failed-dom-read-back \(1 of 78 unverified\)/.test(rb.lines.join('\n')),
    rb.lines.join(' | ')
  );
  check(
    'a head-to-head pair carrying the marker is refused too, not only a vs-floor column',
    verdict(
      summarise({
        runs: [run({ summary: { 'write-bulk': { vsFloor: {}, headToHead: { 'a vs b': { unpublished: 'failed-dom-read-back', unverified: 2, of: 36 } } } } })],
      })
    ).code === 3
  );

  // --- (b) THE REFUSED YIELD CORRECTION — likewise ------------------------
  const refusedRun = () =>
    run({
      summary: { 'write-narrow': { vsFloor: { floor: { min: 1, max: 1 } }, headToHead: {} } },
      correction: {
        'write-narrow': { verdict: 'refused', reason: 'correction-changes-the-verdict', why: 'the adjustment reverses the row' },
      },
    });
  const rc = verdict(summarise({ runs: [refusedRun()] }));
  check('a REFUSED yield correction cannot exit 0', rc.code !== 0, `code ${rc.code}`);
  check('and it exits 4', rc.code === 4, `code ${rc.code}`);
  check(
    'and the refusal carries the adjudicator\'s own reason',
    /write-narrow: correction-changes-the-verdict/.test(rc.lines.join('\n')),
    rc.lines.join(' | ')
  );
  check(
    'a `corrected` verdict is NOT a refusal — only `refused` is',
    verdict(summarise({ runs: [run({ correction: { r: { verdict: 'corrected' } } })] })).code === 0
  );
  check(
    'and neither is `not-owed`, which is the common case',
    verdict(summarise({ runs: [run({ correction: { r: { verdict: 'not-owed' } } })] })).code === 0
  );

  // --- (c) A pageerror BESIDE THE SENTINEL --------------------------------
  const pe = verdict(summarise({ runs: [run({ pageErrors: ['pageerror: Cannot read properties of undefined'] })] }));
  check('a page error recorded beside the sentinel cannot exit 0', pe.code !== 0, `code ${pe.code}`);
  check('and it exits 1, the code this driver already documented for a page error', pe.code === 1, `code ${pe.code}`);

  // --- THE GATES THAT ALREADY EXISTED ARE UNCHANGED, which is half the repair
  const hf = verdict(summarise({ hardFail: 'slim: boom', runs: [] }));
  check('a hard failure still exits 1, exactly as before', hf.code === 1 && /FAILED: slim: boom/.test(hf.lines[0] || ''), hf.lines.join(' | '));
  const cf = verdict(summarise({ contractFailed: 'slim: fixtures', runs: [] }));
  check('a contract self-test failure still exits 1', cf.code === 1, `code ${cf.code}`);
  const or = verdict(summarise({ orderRefused: true, runs: [] }));
  check(
    'the arm-order guard still exits 2, and still says repair the arm',
    or.code === 2 && /Repair the arm, not the guard/.test(or.lines[0] || ''),
    or.lines.join(' | ')
  );

  // --- PRECEDENCE: a stronger refusal never HIDES a weaker one ------------
  const both = verdict(summarise({ orderRefused: true, runs: [readBackRun()] }));
  check(
    'a run that fails several is refused for every one of them — none masks another',
    both.code === 2 && both.lines.some((l) => l.includes('ARM-ORDER GUARD REFUSED')) && both.lines.some((l) => l.includes('never reached the DOM')),
    both.lines.join(' | ')
  );
  const all = verdict(summarise({ hardFail: 'x', orderRefused: true, runs: [readBackRun(), refusedRun()] }));
  check('and the hardest code wins the exit while every line is still printed', all.code === 1 && all.lines.length === 4, `code ${all.code}, ${all.lines.length} lines`);

  const empty = verdict(summarise({ runs: [] }));
  check('a run that took nothing is not a refusal', empty.code === 0 && empty.lines.length === 0);
  check('and neither is one that never ran', verdict(summarise()).code === 0 && verdict(undefined).code === 0);

  return { ok: checks.every((c) => c.ok), checks };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
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

  // The corrected-table fixture, enforced with the same standing: a table
  // that would let a corrected band leave without its original's
  // publication mask may print nothing at all.
  const ts = tableSelfTest();
  console.log(';; ==== HD8 CORRECTED-TABLE SELF-TEST ====');
  for (const c of ts.checks) {
    console.log(`;;   ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}`);
  }
  if (!ts.ok) {
    console.error(
      '[hd8] the cross-run table failed its own corrected-band fixture — a figure the DOM ' +
        'read-back unpublished could leave the table as [CORRECTED]; nothing was measured'
    );
    process.exit(1);
  }

  // AND THE EXIT DECISION ITSELF (rf2-x6g04). Every other self-test here asks
  // whether an adjudicator can REFUSE; this one asks whether its refusal
  // reaches the exit code, which is the fault the other two could not have
  // caught — the table's fixture already proved the marker prints, and the
  // driver exited 0 beneath it anyway.
  const vs = verdictSelfTest();
  console.log(';; ==== HD8 EXIT-DECISION SELF-TEST ====');
  for (const c of vs.checks) {
    console.log(`;;   ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.ok || !c.detail ? '' : '  — ' + c.detail}`);
  }
  if (!vs.ok) {
    console.error(
      '[hd8] the exit decision failed its own fixtures — a refusal this driver prints might not ' +
        'reach its exit code; nothing was measured'
    );
    process.exit(1);
  }

  if (SELFTEST_ONLY) {
    const bad = [...st.checks, ...ts.checks, ...vs.checks].filter((c) => !c.ok);
    console.error(`[hd8] --selftest: ${bad.length === 0 ? 'ALL ADJUDICATORS OK' : 'FAILURES: ' + bad.length}`);
    process.exit(bad.length === 0 ? 0 : 1);
  }

  const sha = revision();
  console.log(`;; ==== HD8 PROVENANCE ====`);
  console.log(`;;   bead        rf2-2rtt6.7 (HD-008, EP-0038)`);
  console.log(`;;   commit      ${sha}`);
  // The repro line carries the environment it was actually run with, not the
  // bare command: a published figure whose repro command does not reproduce it
  // is a figure nobody can check.
  console.log(
    `;;   reproduce   ${ONLY ? `HD8_ONLY=${ONLY} ` : ''}${ROWS ? `HD8_ROWS=${ROWS} ` : ''}node implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs`
  );
  console.log(`;;   build       shadow-cljs release ${BUILD_ID} (:advanced, goog.DEBUG false)`);
  console.log(`;;   node        ${process.version}`);
  console.log(`;;   runs        ${RUNS.map((r) => r.id).join(', ')}${ONLY ? `  (HD8_ONLY=${ONLY})` : ''}`);
  // WHICH ROWS THIS RUN MAY PUBLISH, printed with the provenance and not left
  // to be inferred from the absence of a warning (rf2-b69lw).
  console.log(
    `;;   publishes   ${
      !PARTIAL
        ? 'every row — this is the full three-run sweep, the published shape'
        : DECLARED_ROWS
          ? `${[...DECLARED_ROWS].join(', ')} only (HD8_ROWS); every other row emitted is marked NON-PUBLISHING`
          : 'NOTHING — a partial run with no HD8_ROWS declaration; every row emitted is marked NON-PUBLISHING'
    }`
  );
  // THE EFFECTIVE TOLERANCE, on the record beside the figures it adjudicated.
  // It was not printed, and it does not equal `order_guard`'s own default:
  // the guard defaults to 0.10 and this driver passes 0.35, for the reason
  // above. An adjudication whose threshold a reader has to go and find in the
  // source — or, worse, that an unrecorded `HD8_TOLERANCE=` in someone's shell
  // moved without trace — is the anonymous-ceiling defect again (rf2-f5roa).
  console.log(
    `;;   guard tol   ${TOLERANCE}  (order_guard default 0.10; this driver's stated ` +
      `choice 0.35${process.env.HD8_TOLERANCE ? `; OVERRIDDEN by HD8_TOLERANCE=${process.env.HD8_TOLERANCE}` : ''})`
  );

  build();
  const server = serve();
  const runs = [];
  let hardFail = null;
  try {
    const { chromium } = require('playwright');
    for (const r of RUNS) {
      console.error(`[hd8] run ${r.id} — ${r.why}`);
      let out;
      try {
        out = await runOne(chromium, r);
      } catch (e) {
        // A page that died is a HARD failure and it stops the sweep: the
        // remaining runs would each spend their own budget re-discovering a
        // broken bundle, and no figure from this one is a measurement.
        hardFail = `${r.id}: ${e.message}`;
        break;
      }
      runs.push(out);
      if (out.err) hardFail = `${r.id}: ${out.err}`;
    }
  } finally {
    server.close();
  }

  // The correction contract's self-test, printed and ENFORCED. It runs in the
  // bundle before any clock; a run that reached the far side without it is a
  // run whose publication gate was never checked against its own fixtures.
  let contractFailed = null;
  for (const run of runs) {
    const st = run.contractSelfTest;
    console.log(`\n;; ==== HD8 YIELD-CORRECTION CONTRACT SELF-TEST — ${run.id} ====`);
    if (!st) {
      contractFailed = `${run.id}: the run recorded no contract self-test at all`;
      console.log(';;   MISSING — the run recorded no result');
      continue;
    }
    for (const c of st.checks) {
      console.log(`;;   ${c.ok ? 'ok  ' : 'FAIL'}  ${c.name}${c.detail ? '  — ' + c.detail : ''}`);
    }
    if (!st.ok) contractFailed = `${run.id}: the yield-correction contract failed its own fixtures`;
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

  // THE DECISION HAS ONE SEAT (`verdict`, above). Nothing below reads a
  // refusal on its own: the summary is built, the function decides, its lines
  // are printed and its code is the exit code.
  const decision = verdict(summarise({ hardFail, contractFailed, orderRefused: refused, runs }));
  for (const line of decision.lines) console.error(line);
  if (decision.code !== 0) process.exit(decision.code);
  console.error(
    '[hd8] ok — measured; no arm reads differently for its position in the plan, every published ' +
      'figure survived its DOM read-back, and no yield correction was refused'
  );
}

module.exports = { summarise, verdict, verdictSelfTest };

if (require.main === module) {
  main();
}
