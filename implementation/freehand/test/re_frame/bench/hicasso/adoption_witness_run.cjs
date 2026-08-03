#!/usr/bin/env node
'use strict';
// THE ADOPTION WITNESS — driver (rf2-2rtt6.80).
//
//     node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs
//
// Does the commit adopt the render-phase build on a PUBLIC mount schedule —
// `re-frame.substrate.adapter/render`, no `act`, no `flushSync` — on a page
// whose own clock is fast enough for the question to mean anything? The page
// (`adoption_witness_app.cljs`) carries the method and the reasoning; this
// file builds it, runs it once, and turns its verdict into an exit code.
//
// ## ON DEMAND. NOTHING GATES.
//
// No CI workflow invokes this and none should (rf2-2rtt6.80). The hicasso
// lane's only required check is `test:hicasso-compile`, which compiles this
// page along with every other lane namespace and executes none of them.
//
// RE-RUN IT WHEN THE PINS MOVE: `react`, `react-dom` and `playwright` are
// exact pins in `implementation/package.json` (Playwright pins its own
// Chromium), so the only events that can move this race are reviewed lock or
// posture changes. That is the entire revalidation protocol, and Spec 006
// §Render-phase provisional acquisition and commit adoption records it.
//
// ## Exit codes
//
//   0  ADOPTED      every gap qualified and every cold mount adopted: one
//                   sub-body run, the render's reaction still the cache's
//                   tenant at the first post-subscribe instant, one durable
//                   reference.
//   1  THE RUN FAILED — build, navigation, a page error, a fatal the page
//                   recorded, or a page that produced no verdict at all. No
//                   claim either way.
//   2  REFUSED      the page could not bound its render-to-passive-flush gap
//                   under the reap horizon with margin, so the adoption
//                   integers were never read. NOT a failure of the adoption:
//                   a refusal to adjudicate it here. Read the printed gap —
//                   a noisy box and a genuine React scheduling change both
//                   land here, and the gap is what tells them apart.
//   4  NOT-ADOPTED  the gaps qualified — this page CAN see the race — and the
//                   mechanism still did not adopt. On a qualifying page that
//                   is a mechanism regression rather than a scheduling one.
//
// A Chromium `pageerror` is FATAL, for this directory's usual reason: a
// diagnostic that threw and kept going reports a precise number about a page
// that is not the page under test.
//
// ## The horizon is READ FROM THE SPINE, never copied
//
// `re-frame.substrate.spine/provisional-horizon-ms` is the one place the
// number is written down, and it is private. This driver parses it out of the
// source and hands it to the page in the query string; the page refuses to run
// without it. So there is no second copy of the shipped number to drift, and a
// change to the def's shape refuses loudly instead of silently measuring
// against a stale constant.
//
// ## The qualifying ceiling, and why it is HALF
//
// Adoption happens iff React's passive `useSyncExternalStore` subscribe
// reaches the escrowed reference before the reaper's `setTimeout` fires. A
// page whose gap merely *sat under* the horizon would be adjudicating a
// coin-flip: the verdict would be one scheduler hiccup wide. The ceiling is
// therefore half the horizon — every trial must clear the bar with a 2x
// margin, or the run refuses.
//
// `ADOPTWIT_CEILING_MS` may LOWER that ceiling and may never raise it. The
// asymmetry is the whole point: a lower ceiling can only make the instrument
// decline more often, never pass more easily, so the knob cannot be used to
// green a page. Lowering it is also the honest way to exercise the REFUSED
// path on a machine that is behaving.

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

// The directory's ONE navigation, with its ceiling named (rf2-p9fa3).
const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
// The directory's ONE sentinel wait, raced against the page dying (rf2-f5roa).
const { watchPage } = require('../../freehand/bench/sentinel.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The lane's
// one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');

const BUILD_ID = 'hicasso-bench';
const OUT_DIR = process.env.ADOPTWIT_OUT_DIR || 'out/hicasso-adoption-witness';
const INIT_FN = 're-frame.bench.hicasso.adoption-witness-app/-main';
const OUT = path.join(IMPL, OUT_DIR);
const PORT = Number(process.env.ADOPTWIT_PORT || 8149);
const TAG = 'adoptwit';

const SPINE = path.join(IMPL, 'core', 'src', 're_frame', 'substrate', 'spine.cljs');

// Seventeen single-boundary mounts with a 32 ms settle between them is
// seconds. A minute is generous enough that only a hung page reaches it.
const SENTINEL_TIMEOUT_MS = 60 * 1000;

/** Half. See the block comment above — the margin is the whole design. */
const QUALIFYING_FRACTION = 0.5;

/**
 * The shipped reap horizon, read out of the spine source.
 *
 * The def is `(def ^:private provisional-horizon-ms "…docstring…" N)`, so the
 * value is the only line in the form that is a bare number closing the form.
 * Anything else — a renamed def, a reshaped body, two defs — REFUSES: guessing
 * the horizon would make every verdict below meaningless in a way nobody could
 * see.
 */
function shippedHorizonMs() {
  const src = fs.readFileSync(SPINE, 'utf8');
  const defs = src.match(/\(def\s+\^:private\s+provisional-horizon-ms\b/g) || [];
  if (defs.length !== 1) {
    console.error(
      `[${TAG}] found ${defs.length} definition(s) of provisional-horizon-ms in ` +
        `${path.relative(IMPL, SPINE)} — expected exactly 1. The horizon cannot be ` +
        `read, and this diagnostic will not guess it.`,
    );
    process.exit(1);
  }
  const from = src.indexOf('(def ^:private provisional-horizon-ms');
  const m = /^[ \t]*(\d+(?:\.\d+)?)\)[ \t]*$/m.exec(src.slice(from));
  if (!m) {
    console.error(
      `[${TAG}] provisional-horizon-ms is defined in ${path.relative(IMPL, SPINE)} but ` +
        `its value could not be read (the form no longer ends in a bare number). ` +
        `Refusing to measure against a horizon this driver had to invent.`,
    );
    process.exit(1);
  }
  return Number(m[1]);
}

function qualifyingCeilingMs(horizonMs) {
  const dflt = horizonMs * QUALIFYING_FRACTION;
  const raw = process.env.ADOPTWIT_CEILING_MS;
  if (raw === undefined || raw === '') return dflt;
  const v = Number(raw);
  if (!Number.isFinite(v) || v <= 0) {
    console.error(`[${TAG}] ADOPTWIT_CEILING_MS=${raw} is not a positive number.`);
    process.exit(1);
  }
  if (v > dflt) {
    console.error(
      `[${TAG}] ADOPTWIT_CEILING_MS=${v} would RAISE the qualifying ceiling above its ` +
        `derived value of ${dflt} ms (horizon ${horizonMs} x ${QUALIFYING_FRACTION}). ` +
        `This knob may only lower it: raising it would let a page that cannot see the ` +
        `race adjudicate it, which is the one failure this instrument exists to refuse.`,
    );
    process.exit(1);
  }
  return v;
}

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace when the EDN contains a newline.
const CONFIG_MERGE =
  `{:output-dir "${OUT_DIR}" :asset-path "." ` +
  `:modules {:main {:init-fn ${INIT_FN}}}}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[${TAG}] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N arms (rf2-2rtt6.20)`);
  }
  console.error(`[${TAG}] building :advanced bundle — ${INIT_FN} -> ${OUT_DIR}`);
  shadowBuild({ impl: IMPL, mode: 'release', buildId: BUILD_ID, configMerge: CONFIG_MERGE, tag: TAG });
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve() {
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>Hicasso adoption witness</title></head>' +
      '<body><div id="app"></div><script src="main.js"></script></body></html>',
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

async function runPage(browser, horizonMs, ceilingMs) {
  const page = await browser.newPage();
  // The page's own records, PLUS everything the browser complains about. A
  // console filter that drops React's warnings is a way to run a diagnostic
  // past the message explaining why it read nothing.
  page.on('console', (msg) => {
    const t = msg.text();
    if (t.startsWith(';; ') || t.startsWith(`[${TAG}]`)) console.log(t);
    else if (msg.type() === 'error' || msg.type() === 'warning') {
      console.log(`;; [browser ${msg.type()}] ${t}`);
    }
  });
  // Watching starts BEFORE the navigation (rf2-f5roa): a throw before the
  // sentinel must not cost the full budget before the driver looks.
  const watch = watchPage(page, TAG);
  try {
    await navigate(page, `http://127.0.0.1:${PORT}/?horizon=${horizonMs}&ceiling=${ceilingMs}`, {
      waitUntil: 'commit',
      timeoutMs: NAV_TIMEOUT_MS,
      budget: `the ${SENTINEL_TIMEOUT_MS / 1000}-second wait for window.HICASSO_DONE`,
    });
    await watch.race('window.HICASSO_DONE === true || window.HICASSO_ERROR', {
      timeoutMs: SENTINEL_TIMEOUT_MS,
      budget: `the ${SENTINEL_TIMEOUT_MS / 1000}-second wait for window.HICASSO_DONE`,
    });
    return {
      err: await page.evaluate('window.HICASSO_ERROR || null'),
      verdict: await page.evaluate('window.ADOPTWIT_VERDICT || null'),
      results: await page.evaluate('window.HICASSO_RESULTS || {}'),
      pageErrors: watch.failures.map((f) => `${f.kind}: ${f.detail}`),
    };
  } finally {
    watch.dispose();
    await page.close();
  }
}

(async () => {
  const horizonMs = shippedHorizonMs();
  const ceilingMs = qualifyingCeilingMs(horizonMs);
  console.error(
    `[${TAG}] reap horizon ${horizonMs} ms (read from ${path.relative(IMPL, SPINE)}); ` +
      `qualifying ceiling ${ceilingMs} ms` +
      (process.env.ADOPTWIT_CEILING_MS ? ' (LOWERED by ADOPTWIT_CEILING_MS)' : ''),
  );

  build();
  const server = serve();
  const { chromium } = require('playwright');
  const browser = await chromium.launch();
  const version = browser.version();
  let outcome = null;
  let died = null;
  try {
    outcome = await runPage(browser, horizonMs, ceilingMs);
  } catch (e) {
    died = e.message;
  } finally {
    await browser.close();
    server.close();
  }

  if (died) {
    console.error(`[${TAG}] FAILED: ${died}`);
    process.exit(1);
  }

  console.log(';; ==== ADOPTION WITNESS RUNTIME ====');
  console.log(`;; chromium ${version} (playwright), :advanced, goog.DEBUG false`);
  console.log(`;; horizon ${horizonMs} ms   qualifying ceiling ${ceilingMs} ms`);
  console.log(
    `;; reproduce  node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs`,
  );
  for (const [k, v] of Object.entries(outcome.results)) {
    console.log(`;; ==== ADOPTION WITNESS ${k} ====`);
    console.log(v);
  }

  if (outcome.pageErrors.length > 0) {
    console.error(
      `[${TAG}] FAILED: uncaught page error(s) — every figure above was taken on a page ` +
        `that had already thrown:\n  ${outcome.pageErrors.join('\n  ')}`,
    );
    process.exit(1);
  }
  if (outcome.err) {
    console.error(`[${TAG}] FAILED: ${outcome.err}`);
    process.exit(1);
  }

  switch (outcome.verdict) {
    case 'ADOPTED':
      console.error(
        `[${TAG}] ADOPTED — the commit adopted the render-phase build on the public ` +
          `mount schedule, at a gap comfortably inside the ${horizonMs} ms horizon. ` +
          `A measured margin on this Chromium today, never a React guarantee.`,
      );
      process.exit(0);
    case 'REFUSED':
      console.error(
        `[${TAG}] REFUSED (exit 2) — this page could not bound its render-to-passive-flush ` +
          `gap under ${ceilingMs} ms, so the adoption integers were never read. Nothing is ` +
          `claimed in either direction. The gap statistics above are the datum: a loaded ` +
          `box refuses here, and so does a React scheduling change that moved the passive ` +
          `flush — re-run on a quiet machine to tell them apart.`,
      );
      process.exit(2);
    case 'NOT-ADOPTED':
      console.error(
        `[${TAG}] NOT-ADOPTED (exit 4) — every gap qualified, so this page CAN see the race, ` +
          `and the commit still did not adopt the render's build. On a qualifying page that ` +
          `is a MECHANISM regression (escrow, identity adoption, cache key, release guard), ` +
          `not a scheduling one. See the per-trial faults above.`,
      );
      process.exit(4);
    default:
      console.error(
        `[${TAG}] FAILED: the page finished without recording a verdict ` +
          `(window.ADOPTWIT_VERDICT = ${JSON.stringify(outcome.verdict)}). A diagnostic ` +
          `that reaches its sentinel without adjudicating has not run.`,
      );
      process.exit(1);
  }
})();
