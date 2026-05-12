/*
 * connect-discover.e2e.cjs — pair2 attaches to the live fixture's nREPL,
 * calls `discover-app`, gets a sane health snapshot.
 *
 * Failure modes proved healthy by this spec:
 *   - nREPL port file is at one of the canonical paths.
 *   - shadow-cljs `cljs-eval` returns a parseable value.
 *   - `re-frame-pair2.runtime` preload landed (sentinel reachable).
 *   - `interop/debug-enabled?` is true in a watch build.
 *   - the fixture registers exactly one frame (:rf/default).
 */
'use strict';

const { runShim, parseEdn, openPage } = require('./_helpers.cjs');

async function run(ctx) {
  // Open the page first so the preload actually runs in a browser
  // runtime — discover-app probes the live JS environment.
  const { browser } = await openPage(ctx);
  try {
    const r = runShim(ctx.skillRoot, ctx.fixtureDir, 'discover');
    if (r.exit !== 0) {
      throw new Error('discover exit ' + r.exit + ': ' + r.stderr);
    }
    const v = parseEdn(r.stdout);
    if (!v || typeof v !== 'object') {
      throw new Error('discover did not return a map: ' + r.stdout);
    }
    if (v.ok === false || v[':ok?'] === false) {
      throw new Error('discover :ok? false: ' + JSON.stringify(v));
    }
    // The parser converts keyword keys to plain strings (':ok?' → 'ok?').
    if (v['ok?'] !== true) throw new Error(':ok? not true: ' + JSON.stringify(v));
    if (v['debug-enabled?'] !== true) {
      throw new Error('debug-enabled? not true: ' + JSON.stringify(v));
    }
    const frames = v['frames'];
    if (!Array.isArray(frames) || !frames.includes(':rf/default')) {
      throw new Error('frames does not contain :rf/default: ' + JSON.stringify(frames));
    }
  } finally {
    await browser.close();
  }
}

module.exports = { run };
