#!/usr/bin/env node
// THE rf2-z3vlz DISCRIMINATOR DRIVER — build four bundles, drive six
// pages, and answer (a), (b) or (c).
//
//   node implementation/freehand/test/re_frame/bench/hicasso/z3vlz_run.cjs
//
//   Z3VLZ_ONLY=slim-only,mixed \
//   Z3VLZ_PORT=8171 \
//     node implementation/freehand/test/re_frame/bench/hicasso/z3vlz_run.cjs
//
// Rides rf2-2rtt6.2's `:hicasso-bench` build id through `--config-merge`,
// exactly as `run.cjs` does and for the same reason: HD-017 makes a build-id
// touch a hot-zone edit to `implementation/shadow-cljs.edn`, and four bundles
// would otherwise be four of them. NOTHING here adds a build id.
//
// WHAT THE BUNDLE-COMPOSITION CHECK IS FOR. The whole experiment turns on
// "reagent-slim ALONE, no stock reagent compiled in", so a claim that a
// bundle is single-substrate must be VERIFIED rather than asserted from a
// namespace's `:require` list. Each build is emitted with a source map and
// the driver reads the map's `sources` array — the definitive list of what
// the compiler consumed — and refuses to run a page whose bundle does not
// match its declared composition. A run whose slim-only bundle silently
// contained stock Reagent would answer the wrong question with a precise
// number, which is the failure mode this whole lane is built against.
//
// A Chromium `pageerror` is FATAL, as it is in every adjacent runner: a
// probe that threw and kept going reports on a page that is not the page
// under test.
//
// ## EVERY FIGURE THIS DRIVER PRINTS, IT EXITS ON
//
// It did not. Until this repair the only exit-bearing checks were the
// build, the bundle-composition check and a page error — so a run in
// which a mount did not verify, in which a slim page stopped reading
// `0/0/12`, in which the app-db witness went missing, or in which an
// unmount threw between the two arms, printed a `?` or a different number
// beside `[z3vlz] ok` and exited 0. A count that is DISPLAYED but not
// GATED is decoration, and this rig exists because an ungated count in
// HD-008 nearly published `reagent-slim narrow write is 2-6x faster than
// a top-down re-render` off a page that never changed.
//
// So every page now carries a CONTRACT in data — `PAGE_CONTRACT` below —
// and the driver adjudicates the probe's own figures against it. The
// contract is exactly the matrix this rig published
// (`docs/design/hicasso/studio/slim-non-reactive-arm-diagnosis.md`), and
// it is stated per page rather than derived, so a drift has to be
// declared to be accepted.
//
// The figures come from `window.Z3VLZ_GATES`, which the probe publishes
// as a flat JS record beside its EDN. Scraping the EDN with regexes would
// be a second expression of the same arithmetic, and a regex that stopped
// matching would report `?` and PASS — the same fail-open shape.
//
// EVERY failed gate is named, never the last one: `failures` is a list,
// not a slot, because a run that failed two things must not report one.
// That is `p0_run.cjs`'s shape and this is the same rule.
//
// THE CONTRACT IS ENFORCED IN THE `Z3VLZ_OPT=none` AID TOO. The aid is a
// rig-debugging build and never a source of a finding, so a divergence
// there is a finding ABOUT THE AID and the driver names it rather than
// quietly exempting it. There is no knob to relax the contract: the
// repair for a refusal is the arm or the declared contract, never the
// gate.
//
// EXIT CODES
//   0  every declared bundle matched, every page ran, and every page met
//      its declared contract
//   1  a build failed, a bundle did not match its declared composition, a
//      page threw, the probe recorded a fatal, a page published no gate
//      record, or a page's figures drifted from its declared contract
//
// The arm-order guard owns exit 2 on the timed lanes. It is NOT ENGAGED
// here and the run says so in its own record: this driver publishes no
// timed figure and no arm-to-arm ratio, so there is nothing for the guard
// to adjudicate. The only numbers are write and DOM read-back counts.

'use strict';

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { navigate, NAV_TIMEOUT_MS } = require('../../freehand/bench/navigate.cjs');
// One build id, N programs, so nothing may cache between them (rf2-2rtt6.20).
// This driver needs it MOST: it builds four different variants back to back in
// a single run, so it was poisoning its own later rungs with its earlier ones
// without any second driver being involved.
const { resetLaneBuildCache } = require('../../freehand/bench/lane_cache.cjs');
// shadow-cljs exits 0 on WARNINGS, so a status check is not a gate. The
// lane's one build door refuses a warned build (rf2-2rtt6.73).
const { shadowBuild } = require('./lane_build.cjs');

const IMPL = path.resolve(__dirname, '../../../../..');
const BUILD_ID = 'hicasso-bench';
const PORT = Number(process.env.Z3VLZ_PORT || 8171);
const SENTINEL_TIMEOUT_MS = 5 * 60 * 1000;

// ---------------------------------------------------------------------------
// THE DECLARED PAGE CONTRACTS
// ---------------------------------------------------------------------------
//
// One row of the published matrix, as data. `of` is the probe's
// `writes-n`; `unverified` is how many of those writes the DOM did not
// follow inside the write's own window; `rungs` is the escalation-ladder
// histogram, and it is gated because LATE and NEVER are different
// findings and the whole conclusion turns on which one this is. A run
// whose slim page moved from `macrotask=12` to `never=12` would be a
// different result wearing the same `12 of 12`.
const SYNC12 = { of: 12, unverified: 0, rungs: { sync: 12 } };
const LATE12 = { of: 12, unverified: 12, rungs: { macrotask: 12 } };

// reagent-slim installed: the DOM follows every write under the adapter's
// own drain contract and under an immediate drain, and is one macrotask
// late when the harness yields first. Stock Reagent installed: all three
// orders commit at `:sync`, because its queue is rAF-scheduled and a
// microtask has not emptied it.
const SLIM_LATE = {
  'inside-drain': SYNC12,
  'drain-immediately': SYNC12,
  'yield-then-drain': LATE12,
};
const STOCK_SYNC = {
  'inside-drain': SYNC12,
  'drain-immediately': SYNC12,
  'yield-then-drain': SYNC12,
};

// BOTH ARMS TAKE THE SAME CONTRACT, and that is the finding rather than a
// convenience: the raw positive control — a plain substrate component
// reading a plain substrate atom, no frame, no `subscribe`, no
// `:adapter/*` hook — moves in lockstep with the `reg-view` arm in every
// bundle. That is what killed the late-binding hypothesis, so it is what
// the contract asserts.
const ORDERS = ['inside-drain', 'drain-immediately', 'yield-then-drain'];

// Each rung adds ONE namespace to the one above it. The `expect` map is
// checked against the build's own source map before the page is driven;
// each page's `contract` is checked against the probe's own figures after
// it has run.
const VARIANTS = [
  {
    name: 'slim-only',
    initFn: 're-frame.bench.hicasso.z3vlz-slim-only/-main',
    outDir: 'out/z3vlz-slim-only',
    expect: { reagent: false, reagent2: true, uix: false },
    pages: [
      { label: 'slim-only / install=reagent-slim', query: '', contract: SLIM_LATE },
    ],
  },
  {
    name: 'slim+reagent',
    initFn: 're-frame.bench.hicasso.z3vlz-slim-reagent/-main',
    outDir: 'out/z3vlz-slim-reagent',
    expect: { reagent: true, reagent2: true, uix: false },
    pages: [
      { label: 'slim+reagent / install=reagent-slim', query: '', contract: SLIM_LATE },
      {
        label: 'slim+reagent / install=reagent (control)',
        query: '?install=reagent',
        contract: STOCK_SYNC,
      },
    ],
  },
  {
    name: 'slim+uix',
    initFn: 're-frame.bench.hicasso.z3vlz-slim-uix/-main',
    outDir: 'out/z3vlz-slim-uix',
    expect: { reagent: false, reagent2: true, uix: true },
    pages: [
      { label: 'slim+uix / install=reagent-slim', query: '', contract: SLIM_LATE },
    ],
  },
  {
    name: 'mixed',
    initFn: 're-frame.bench.hicasso.z3vlz-mixed/-main',
    outDir: 'out/z3vlz-mixed',
    expect: { reagent: true, reagent2: true, uix: true },
    pages: [
      { label: 'mixed / install=reagent-slim', query: '', contract: SLIM_LATE },
      {
        label: 'mixed / install=reagent (control)',
        query: '?install=reagent',
        contract: STOCK_SYNC,
      },
    ],
  },
];

function selected() {
  const only = (process.env.Z3VLZ_ONLY || '').trim();
  if (!only) return VARIANTS;
  const want = new Set(only.split(',').map((s) => s.trim()).filter(Boolean));
  const got = VARIANTS.filter((v) => want.has(v.name));
  if (got.length === 0) {
    console.error(`[z3vlz] Z3VLZ_ONLY=${only} matched no variant`);
    process.exit(1);
  }
  return got;
}

function build(variant) {
  // The lane's cache rule, before anything reads the cache — and PER VARIANT,
  // because each of the four is a different program. `lane_cache.cjs` carries
  // the measurement and the rejected alternatives.
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(`[z3vlz] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N variants (rf2-2rtt6.20)`);
  }
  console.error(`[z3vlz] building :advanced — ${variant.name} -> ${variant.outDir}`);
  // ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
  // whitespace when the EDN contains a newline and then reports `EOF while
  // reading` from a fragment.
  // `Z3VLZ_OPT=none` builds the SAME entries unoptimised. It is a
  // rig-debugging aid ONLY and never a source of a finding: `goog.DEBUG` is
  // true there, which puts the whole Spec 009 instrumentation seam back on
  // the subscription path this probe walks. Every conclusion in the bead is
  // taken from the default `:advanced` build, which is the artefact a
  // consumer ships and the one HD-008 measured.
  // `release` is what carries `:advanced`; the unoptimised aid is the
  // `compile` command, because `release` refuses `:optimizations :none`
  // outright ("optimizations set to :none, can't optimize").
  const dev = process.env.Z3VLZ_OPT === 'none';
  const merge =
    `{:output-dir "${variant.outDir}" :asset-path "." ` +
    `:compiler-options {:source-map true} ` +
    `:modules {:main {:init-fn ${variant.initFn}}}}`;
  shadowBuild({
    impl: IMPL,
    mode: dev ? 'compile' : 'release',
    buildId: BUILD_ID,
    configMerge: merge,
    tag: 'z3vlz',
  });
}

// `reagent2/...` also contains the substring `reagent`, so stock Reagent is
// matched by a path segment that is EXACTLY `reagent` — the one distinction
// the whole experiment rests on.
const STOCK_REAGENT = /(^|[\\/])reagent[\\/](core|ratom|dom|impl)[\\/.]/;
const SLIM_REAGENT = /(^|[\\/])reagent2[\\/]/;
const UIX = /(^|[\\/])uix[\\/]/;

function composition(variant) {
  const mapFile = path.join(IMPL, variant.outDir, 'main.js.map');
  if (!fs.existsSync(mapFile)) {
    console.error(
      `[z3vlz] FAILED: no source map at ${mapFile} — the bundle-composition check ` +
        `cannot run, and an unverified single-substrate claim is the one thing this ` +
        `experiment may not assert`
    );
    process.exit(1);
  }
  // shadow-cljs emits an INDEXED source map (`sections`), one section per
  // compiled input, so the file has no top-level `sources` array. Both
  // shapes are read here: a driver that silently found zero sources would
  // report "stock reagent absent" for every bundle, including the ones that
  // contain it, and the bundle-composition check would pass vacuously.
  const raw = JSON.parse(fs.readFileSync(mapFile, 'utf8'));
  const sources = [
    ...(raw.sources || []),
    ...(raw.sections || []).flatMap((s) => (s.map && s.map.sources) || []),
  ];
  if (sources.length === 0) {
    console.error(
      `[z3vlz] FAILED: ${mapFile} yielded no source list — the bundle-composition ` +
        `check would pass vacuously for every bundle`
    );
    process.exit(1);
  }
  return {
    sources: sources.length,
    reagent: sources.some((s) => STOCK_REAGENT.test(s)),
    reagent2: sources.some((s) => SLIM_REAGENT.test(s)),
    uix: sources.some((s) => UIX.test(s)),
    stockFiles: sources.filter((s) => STOCK_REAGENT.test(s)).slice(0, 6),
  };
}

function checkComposition(variant) {
  const got = composition(variant);
  const want = variant.expect;
  const bad = ['reagent', 'reagent2', 'uix'].filter((k) => got[k] !== want[k]);
  console.log(
    `;; ==== Z3VLZ BUNDLE ${variant.name} ====\n` +
      `;; compiled sources: ${got.sources}  stock-reagent: ${got.reagent}  ` +
      `reagent2: ${got.reagent2}  uix: ${got.uix}` +
      (got.stockFiles.length ? `\n;; stock-reagent files: ${got.stockFiles.join(' ')}` : '')
  );
  if (bad.length) {
    console.error(
      `[z3vlz] FAILED: bundle ${variant.name} does not match its declared ` +
        `composition (${bad.map((k) => `${k}: want ${want[k]}, got ${got[k]}`).join('; ')}). ` +
        `Every conclusion below would be drawn from a bundle that is not the one named.`
    );
    process.exit(1);
  }
  return got;
}

const MIME = { '.js': 'text/javascript', '.html': 'text/html', '.map': 'application/json' };

function serve(outDir) {
  const OUT = path.join(IMPL, outDir);
  fs.writeFileSync(
    path.join(OUT, 'index.html'),
    '<!doctype html><html><head><meta charset="utf-8"><title>z3vlz</title></head>' +
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

async function drive(page, url) {
  const pageErrors = [];
  // Resolved by the FIRST uncaught page error, and raced against the
  // done-sentinel below. A throw that escapes the probe's own promise chain
  // leaves `window.HICASSO_DONE` unset for ever, so without this race the
  // driver would sit out its whole sentinel and then report a TIMEOUT —
  // burying the actual stack under a budget failure it did not have.
  let onFirstError;
  const firstError = new Promise((res) => {
    onFirstError = res;
  });
  const onError = (e) => {
    pageErrors.push({ message: e.message, stack: e.stack });
    console.error('[z3vlz] PAGE ERROR:', e.message);
    if (e.stack) console.error(e.stack);
    onFirstError('pageerror');
  };
  page.on('pageerror', onError);

  // Playwright's `pageerror` reports `name: message`, and an `:advanced`
  // bundle renames the constructor of any Error subclass — so a CLJS
  // `ExceptionInfo` with an empty message arrives as a two-letter munged
  // token and nothing else. The page-side listener keeps what the driver
  // side cannot see: the raw stack and, for a fail-loud `ex-info`, its
  // `ex-data` (which is where every re-frame diagnostic actually lives).
  await page.addInitScript(() => {
    window.addEventListener('error', (ev) => {
      const e = ev.error;
      let data = null;
      try {
        data = e && e.data ? JSON.stringify(e.data, (k, v) => (v === undefined ? null : v)) : null;
      } catch (_) {
        data = e && e.data ? String(e.data) : null;
      }
      window.Z3VLZ_UNCAUGHT = {
        str: String(e),
        message: e && e.message,
        stack: e && e.stack,
        data,
      };
    });
  });

  // `'commit'`, not `'load'`: the bundle's `:init-fn` runs inside the
  // `<script>`, so the probe is already running while the document is
  // loading and `load` cannot fire until it is over.
  await navigate(page, url, {
    waitUntil: 'commit',
    timeoutMs: NAV_TIMEOUT_MS,
    budget: `the ${SENTINEL_TIMEOUT_MS / 60000}-minute wait for window.HICASSO_DONE`,
  });
  await Promise.race([
    page.waitForFunction('window.HICASSO_DONE === true || window.HICASSO_ERROR', null, {
      timeout: SENTINEL_TIMEOUT_MS,
    }),
    firstError,
  ]);
  const err = await page.evaluate('window.HICASSO_ERROR || null');
  const results = await page.evaluate('window.HICASSO_RESULTS || {}');
  const gates = await page.evaluate('window.Z3VLZ_GATES || null');
  const uncaught = await page.evaluate('window.Z3VLZ_UNCAUGHT || null');
  if (uncaught) {
    console.error('[z3vlz] UNCAUGHT (page-side capture):');
    console.error(`  toString: ${uncaught.str}`);
    console.error(`  message:  ${uncaught.message}`);
    console.error(`  ex-data:  ${uncaught.data}`);
    console.error(`  stack:    ${uncaught.stack}`);
  }
  page.off('pageerror', onError);
  return { err, results, gates, pageErrors, uncaught };
}

// ---------------------------------------------------------------------------
// ADJUDICATION — the declared contract against the figures the page produced
// ---------------------------------------------------------------------------

// A histogram as one canonical, order-independent string, so `{sync: 12}`
// compares as a whole rather than key by key. A rung that appeared and
// one that vanished are both drift and both have to be named.
const rungs = (h) =>
  Object.keys(h || {})
    .sort()
    .map((k) => `${k}=${h[k]}`)
    .join(',') || '(none)';

// `wantDb` is `true` for the arm that has an app-db behind it and `null`
// for the raw control, which legitimately has none. Requiring `null`
// rather than ignoring it is deliberate: `:db-followed-all?` used to be
// gated on `(some :db-followed? rs)`, so it VANISHED on the all-false
// case — the arm looked exactly like the control precisely when every
// write had failed the app-db witness. The driver now refuses an absent
// slot on the arm and an unexpectedly present one on the control, so the
// trap cannot come back silently.
function checkArm(label, armName, got, contract, wantDb, failures) {
  const at = `${label} / ${armName}`;
  if (!got) {
    failures.push(`${at}: the page published no gate record for this arm`);
    return;
  }
  if (!got.mountOk) {
    failures.push(
      `${at}: the MOUNT did not verify at the DOM — every write figure below it ` +
        `was taken against a page that had not rendered correctly to begin with`
    );
  }
  if (got.teardown && got.teardown.length) {
    failures.push(
      `${at}: ${got.teardown.length} teardown failure(s) — the arm's roots, its ` +
        `boundaries and their subscriptions are still standing, so whatever ran ` +
        `next on this document was measured on a contaminated page: ` +
        got.teardown.join('; ')
    );
  }
  for (const order of ORDERS) {
    const want = contract[order];
    const g = (got.orders || {})[order];
    if (!g) {
      failures.push(`${at} / ${order}: no figures published for this order`);
      continue;
    }
    if (g.of !== want.of) {
      failures.push(`${at} / ${order}: ${g.of} writes, contract declares ${want.of}`);
    }
    if (g.unverified !== want.unverified) {
      failures.push(
        `${at} / ${order}: ${g.unverified} unverified of ${g.of}, contract declares ` +
          `${want.unverified} of ${want.of}`
      );
    }
    const gotRungs = rungs(g.rungs);
    const wantRungs = rungs(want.rungs);
    if (gotRungs !== wantRungs) {
      failures.push(
        `${at} / ${order}: escalation ladder reads ${gotRungs}, contract declares ` +
          `${wantRungs} — LATE and NEVER are different findings and this rig's ` +
          `conclusion is which one it is`
      );
    }
    if (wantDb === null && g.dbFollowedAll !== null) {
      failures.push(
        `${at} / ${order}: the raw control published an app-db witness ` +
          `(${g.dbFollowedAll}) and it has no app-db to witness`
      );
    } else if (wantDb === true && g.dbFollowedAll !== true) {
      failures.push(
        `${at} / ${order}: app-db witness is ${g.dbFollowedAll} — ` +
          (g.dbFollowedAll === null
            ? 'the slot is ABSENT, which is what the `(some :db-followed? rs)` trap did ' +
              'on the all-false case and is never a pass'
            : 'app-db did not take the new value on every write, so a failed read-back ' +
              'can no longer be attributed to the view leg')
      );
    }
  }
}

function checkPage(label, contract, gates, failures) {
  if (!gates) {
    failures.push(
      `${label}: the page published no window.Z3VLZ_GATES — its declared contract ` +
        `could not be checked, and an unchecked contract is exactly the fail-open ` +
        `this driver was repaired for`
    );
    return;
  }
  checkArm(label, 'subs-arm', gates.subs, contract, true, failures);
  checkArm(label, 'raw-control', gates.raw, contract, null, failures);
}

(async () => {
  const variants = selected();
  const { chromium } = require('playwright');
  const browser = await chromium.launch();
  const version = browser.version();
  const rows = [];
  // EVERY failed gate, not the last one and not a bare boolean. A run that
  // failed two pages must name two pages: `p0_run.cjs` carries the same
  // list for the same reason.
  const failures = [];

  for (const variant of variants) {
    build(variant);
    const comp = checkComposition(variant);
    const server = serve(variant.outDir);
    try {
      for (const p of variant.pages) {
        const page = await browser.newPage();
        page.on('console', (msg) => {
          const t = msg.text();
          if (t.startsWith(';; ') || t.startsWith('[z3vlz]')) console.log(t);
        });
        console.log(`;; ==== Z3VLZ PAGE ${p.label} ====`);
        const outcome = await drive(page, `http://127.0.0.1:${PORT}/${p.query}`);
        await page.close();
        for (const [k, v] of Object.entries(outcome.results)) {
          console.log(`;; ---- ${p.label} :${k}\n${v}`);
        }
        if (outcome.pageErrors.length) {
          failures.push(
            `${outcome.pageErrors.length} uncaught page error(s) on ` +
              `${p.label} — every figure above was taken on a page that had already ` +
              `thrown:\n  ${outcome.pageErrors.map((e) => e.stack || e.message).join('\n  ')}`
          );
        }
        if (outcome.err) {
          failures.push(`${p.label}: ${outcome.err}`);
        }
        // THE DECLARED CONTRACT. Checked even when the page reported a
        // fatal: a page that failed two ways must say so.
        checkPage(p.label, p.contract, outcome.gates, failures);
        rows.push({ page: p.label, comp, verdict: outcome.results.verdict || null });
      }
    } finally {
      server.close();
    }
  }
  await browser.close();

  console.log(`;; ==== Z3VLZ RUNTIME ====`);
  console.log(`;; chromium ${version} (playwright), :advanced, goog.DEBUG false`);
  console.log(`;; ==== Z3VLZ SUMMARY ====`);
  // The three ORDER rows side by side. `inside-drain` is the substrate's own
  // documented contract and is therefore the row that answers the bead's
  // question about the ADAPTER; `yield-then-drain` is `lane/verified-write!`'s
  // shape and is the row that answers the question about the HARNESS.
  const order = (v, k) =>
    v ? ((v.match(new RegExp(`:${k} "([^"]+)"`)) || [])[1] || '?') : '?';
  console.log(
    `;; ${'page'.padEnd(40)} ${'stock?'.padEnd(7)} ${'mount'.padEnd(6)} ` +
      `${'inside-drain'.padEnd(20)} ${'drain-immediately'.padEnd(20)} yield-then-drain`
  );
  for (const r of rows) {
    const mount = r.verdict ? /:mount-correct\?\s+true/.test(r.verdict) : null;
    console.log(
      `;; ${r.page.padEnd(40)} ${String(r.comp.reagent).padEnd(7)} ${String(mount).padEnd(6)} ` +
        `${order(r.verdict, 'inside-drain').padEnd(20)} ` +
        `${order(r.verdict, 'drain-immediately').padEnd(20)} ` +
        `${order(r.verdict, 'yield-then-drain')}`
    );
  }
  console.log(
    `;; arm-order guard: NOT ENGAGED — no timed figure and no arm-to-arm ratio is ` +
      `published by this driver, so there is nothing to adjudicate.`
  );
  console.log(
    `;; page contracts: ${rows.length} page(s) adjudicated against the declared ` +
      `matrix — ${failures.length === 0 ? 'all met' : `${failures.length} refusal(s)`}`
  );

  if (failures.length) {
    for (const f of failures) console.error(`[z3vlz] FAILED: ${f}`);
    process.exit(1);
  }
  console.error('[z3vlz] ok');
})();
