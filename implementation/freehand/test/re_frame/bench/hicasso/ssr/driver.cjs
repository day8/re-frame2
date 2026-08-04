#!/usr/bin/env node
'use strict';
// THE HICASSO SSR DRIVER — build once, then BAKE or SERVE (rf2-2rtt6.86
// clauses 3, 4 and 5).
//
//     node freehand/test/re_frame/bench/hicasso/ssr/driver.cjs bake
//     node freehand/test/re_frame/bench/hicasso/ssr/driver.cjs serve [--port 8137]
//
// One renderer, run in two places: `bake` writes the static
// HTML + payload corpus, `serve` answers real requests with the same
// entry, and neither has a renderer of its own — both call
// `re-frame.bench.hicasso.ssr.node`'s API through the compiled bundle.
//
// ## NO EDIT TO shadow-cljs.edn, deliberately (clause 5)
//
// The bead says to carry this on existing node-build infrastructure first
// and mint `:hicasso-ssr-node` only if needed. It is not needed: the build
// rides `:freehand-bench-node` — the tree's existing `:node-script` — and
// supplies its own `:main` and `:output-to` through `--config-merge`,
// exactly as every arm in this lane rides `:hicasso-bench` with its own
// `:init-fn` and exactly as `b6_prod_run.cjs` rides `:freehand-release`.
// HD-017 makes a build-id touch a hot-zone, sequenced dispatch; this lane
// was built so a sibling never has to pay one, and an SSR entry is no
// more entitled to than an arm is.
//
// `:hicasso-bench` — the lane's own id — could NOT serve here: it is a
// `:browser` target, and `renderToString` wants Node's `server.node.js`
// through Node's own conditional exports.
//
// ## The cache rule (rf2-2rtt6.20), and why it is cheap here
//
// shadow-cljs derives the build cache directory from the build id alone,
// before any `--config-merge` is applied, so two programs under one id
// share one cache entry. The lane's answer is to clear it, and this
// driver does the same. It is cheaper here than in the browser lane: a
// `:node-script` uses `:js-provider :require`, so there is no shadow-js
// npm-conversion index — the exact artefact rf2-2rtt6.20 isolated as the
// carrier — and what is discarded is a plain per-namespace CLJS cache.
// The cost is a cold `npm run bench:freehand-node` afterwards, which is a
// manual bench for a withdrawn programme and not a gate.
//
// ## Warnings are failures, on BOTH sides of the build
//
// `lane_build.cjs` refuses a shadow build that merely warned. This driver
// extends the same rule to the RENDER: React reports a bad server render
// through `console.error` while returning markup and exiting 0 — a
// hydration-relevant defect that would otherwise ride into a baked
// fixture behind a green run. Every render here happens with the console
// captured, and any React diagnostic is a refusal.

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const { shadowBuild } = require('../lane_build.cjs');
const { resetLaneBuildCache } = require('../../../freehand/bench/lane_cache.cjs');

const IMPL = path.resolve(__dirname, '../../../../../..');
const BUILD_ID = 'freehand-bench-node';
const OUTPUT_TO = 'out/hicasso-ssr-node.js';
const BUNDLE = path.join(IMPL, OUTPUT_TO);
const BAKE_DIR = path.join(IMPL, 'out', 'hicasso-ssr-fixtures');
const TAG = 'hicasso-ssr';

// ONE LINE, deliberately: shadow-cljs's CLI re-splits `--config-merge` on
// whitespace once the EDN contains a newline, then reports `EOF while
// reading` from a fragment.
const CONFIG_MERGE =
  `{:main re-frame.bench.hicasso.ssr.node/-main :output-to "${OUTPUT_TO}"}`;

function build() {
  if (resetLaneBuildCache(IMPL, BUILD_ID)) {
    console.error(
      `[${TAG}] cleared .shadow-cljs/builds/${BUILD_ID} — one build id, N programs (rf2-2rtt6.20)`,
    );
  }
  console.error(`[${TAG}] building the SSR node entry -> ${OUTPUT_TO}`);
  shadowBuild({ impl: IMPL, mode: 'compile', buildId: BUILD_ID, configMerge: CONFIG_MERGE, tag: TAG });
}

/** The API the compiled bundle publishes. Requiring it IS the boot. */
function loadApi() {
  require(BUNDLE);
  const api = globalThis.HICASSO_SSR;
  if (!api) {
    console.error(
      `[${TAG}] the bundle loaded but published no API on globalThis.HICASSO_SSR — ` +
        `did :main stop being re-frame.bench.hicasso.ssr.node/-main?`,
    );
    process.exit(1);
  }
  return api;
}

/**
 * Run `fn` with `console.error` / `console.warn` captured, and REFUSE if
 * React said anything. React's server renderer reports a bad render — a
 * `value` without a change handler, an unknown prop, a hook that cannot
 * run on the server — through the console while still returning markup
 * and still exiting 0. A bake that swallowed that would publish a fixture
 * the hydration bead then fails on, one bead later and one layer away.
 */
function renderQuietly(label, fn) {
  const said = [];
  const realError = console.error;
  const realWarn = console.warn;
  console.error = (...a) => said.push(['error', a.map(String).join(' ')]);
  console.warn = (...a) => said.push(['warn', a.map(String).join(' ')]);
  let out;
  try {
    out = fn();
  } finally {
    console.error = realError;
    console.warn = realWarn;
  }
  if (said.length > 0) {
    console.error(`\n[${TAG}] REFUSED — ${label} rendered, but React reported ${said.length} diagnostic(s):`);
    for (const [kind, text] of said) console.error(`[${TAG}]   ${kind}: ${text}`);
    console.error(
      `[${TAG}] A server render that warns is a hydration defect that has not ` +
        `happened yet. Fix the render; do not bake the fixture.`,
    );
    process.exit(1);
  }
  return out;
}

const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex');

// A FIGURE CLAIMED IN BYTES IS MEASURED IN BYTES (rf2-2rtt6.114).
//
// `String.prototype.length` counts UTF-16 code units, which agree with
// UTF-8 bytes only for ASCII — and this corpus is not ASCII. Every row's
// title carries an em dash and the `defhost` fallback carries an ellipsis,
// so the manifest claimed 3101 for a `dogfood-snapshot` document of which
// 3119 bytes were written, and 485 for a `defhost-ssr-policy` document of
// which 491 were. An astral-plane character would have doubled the error
// again. The digest rows were never affected: `sha256` above hashes with
// an explicit 'utf8' encoding, so this was a metrics defect and not a
// determinism one.
const utf8Bytes = (s) => Buffer.byteLength(s, 'utf8');

// ---------------------------------------------------------------------------
// bake
// ---------------------------------------------------------------------------

function bake(api) {
  fs.rmSync(BAKE_DIR, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  fs.mkdirSync(BAKE_DIR, { recursive: true });

  const rows = [];
  for (const id of api.ids) {
    const r = renderQuietly(id, () => api.renderTwice(id));

    // Clause 2, and the driver's half of it: the entry already compared
    // the two documents byte-for-byte; this hashes them, because a hash is
    // what a manifest can carry and what a later run can be compared to.
    const a = sha256(r.first.document);
    const b = sha256(r.second.document);
    if (!r.identical || a !== b) {
      console.error(
        `\n[${TAG}] REFUSED — ${id} rendered two DIFFERENT documents from the ` +
          `same bundle and the same snapshot` +
          (r.differsAt >= 0 ? ` (first difference at character ${r.differsAt})` : ''),
      );
      console.error(`[${TAG}]   sha256 #1 ${a}`);
      console.error(`[${TAG}]   sha256 #2 ${b}`);
      console.error(
        `[${TAG}] Determinism is the property that makes a baked fixture a ` +
          `fixture. Find the nondeterminism; do not re-run until it agrees.`,
      );
      process.exit(1);
    }
    // The two renders took two different per-request frames and produced
    // the same bytes — which is the gensym's invisibility, demonstrated.
    if (r.first.frameId === r.second.frameId) {
      console.error(
        `\n[${TAG}] REFUSED — ${id}'s two renders shared a frame id ` +
          `(${r.first.frameId}); the per-request frame is not per-request.`,
      );
      process.exit(1);
    }

    const wrote = [
      ['documentBytes', `${id}.html`, r.first.document],
      ['bodyBytes', `${id}.body.html`, r.first.html],
      ['payloadBytes', `${id}.payload.edn`, r.first.payloadEdn],
    ].map(([field, name, text]) => {
      const file = path.join(BAKE_DIR, name);
      fs.writeFileSync(file, text, 'utf8');
      return [field, file, utf8Bytes(text)];
    });

    // THE CLAIM IS CHECKED AGAINST THE FILE IT DESCRIBES. Two independent
    // derivations of one number — what the encoder says the string weighs,
    // and what the file system says landed — so a manifest column can no
    // longer drift from the corpus it names without this refusing. It is
    // what caught nothing for the whole life of the defect above, because
    // nothing was comparing.
    for (const [field, file, claimed] of wrote) {
      const onDisk = fs.statSync(file).size;
      if (claimed !== onDisk) {
        console.error(
          `\n[${TAG}] REFUSED — ${id}'s ${field} claims ${claimed} but ` +
            `${path.relative(IMPL, file)} is ${onDisk} bytes on disk.`,
        );
        console.error(
          `[${TAG}] A size figure that disagrees with its own file is the ` +
            `defect rf2-2rtt6.114 repaired. Fix the accounting; do not bake the manifest.`,
        );
        process.exit(1);
      }
    }

    const [[, , documentBytes], [, , bodyBytes], [, , payloadBytes]] = wrote;
    rows.push({
      id,
      documentBytes,
      bodyBytes,
      payloadBytes,
      renderHash: r.first.renderHash,
      sha256: a,
      frames: [r.first.frameId, r.second.frameId],
    });
    console.error(
      `[${TAG}] ${id.padEnd(24)} ${String(documentBytes).padStart(7)} B  sha256 ${a.slice(0, 16)}…`,
    );
  }

  const manifest = {
    bead: 'rf2-2rtt6.86',
    generatedBy: 'freehand/test/re_frame/bench/hicasso/ssr/driver.cjs bake',
    // No timestamp: a manifest that changes on every run cannot be
    // compared to the previous one, which is the only thing it is for.
    rows,
  };
  fs.writeFileSync(
    path.join(BAKE_DIR, 'manifest.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8',
  );

  console.error(`[${TAG}] ok — ${rows.length} fixtures baked to ${path.relative(IMPL, BAKE_DIR)}`);
}

// ---------------------------------------------------------------------------
// serve — the live demo (clause 4)
// ---------------------------------------------------------------------------

// PAGE-LEVEL, and explicitly NOT a production host: Spec 011's HTTP
// response contract — the response accumulator, cookies, redirects, the
// CRLF fail-fast — stays `ssr-ring`'s. This exists so that live,
// per-request Hicasso SSR is DEMONSTRABLE at the P2 sitting, the way the
// adapters' live SSR already is.
function serve(api, port) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');

    if (url.pathname === '/main.js') {
      // There is no client bundle in this bead — the hydration door is
      // rf2-2rtt6.84's. Answering with a comment rather than a 404 keeps
      // the demo's console clean and says why.
      res.writeHead(200, { 'content-type': 'text/javascript; charset=utf-8' });
      res.end('// no client bundle in this bead — the hydration door is rf2-2rtt6.84\n');
      return;
    }

    // The request's OWN state, so "per-request" is something a reader can
    // see rather than something this comment claims.
    const n = Math.max(0, Math.min(200, Number(url.searchParams.get('todos') ?? 5) || 0));
    const started = process.hrtime.bigint();
    const out = renderQuietly(`GET ${req.url}`, () => api.renderDogfood(n));
    const ms = Number(process.hrtime.bigint() - started) / 1e6;

    console.error(
      `[${TAG}] ${req.method} ${req.url} -> 200, ${n} to-dos, ` +
        `${utf8Bytes(out.document)} B, frame ${out.frameId}, ${ms.toFixed(1)} ms`,
    );
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      // The frame id on a header, because the demo's whole claim is that
      // each request got its own and it was destroyed before the response.
      'x-hicasso-ssr-frame': out.frameId,
    });
    res.end(out.document);
  });

  server.listen(port, () => {
    console.error(`[${TAG}] live SSR on http://127.0.0.1:${port}/  (try /?todos=12)`);
  });
  return server;
}

// ---------------------------------------------------------------------------

if (require.main === module) {
  const argv = process.argv.slice(2);
  const mode = argv.find((a) => !a.startsWith('-')) || 'bake';
  const portArg = argv.indexOf('--port');
  const port = Number(portArg >= 0 ? argv[portArg + 1] : process.env.HICASSO_SSR_PORT || 8137);

  if (mode !== 'bake' && mode !== 'serve') {
    console.error(`[${TAG}] unknown mode ${JSON.stringify(mode)} — expected 'bake' or 'serve'`);
    process.exit(1);
  }

  build();
  const api = loadApi();

  if (mode === 'bake') bake(api);
  else serve(api, port);
}

module.exports = { sha256, utf8Bytes, renderQuietly, BAKE_DIR, BUILD_ID, OUTPUT_TO };
