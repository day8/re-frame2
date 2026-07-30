'use strict';
// THE HICASSO LANE'S ONE CACHE RULE — rf2-2rtt6.20.
//
// HD-017 gives the whole P0 lane a SINGLE build id, `:hicasso-bench`, because
// `implementation/shadow-cljs.edn` is hot-zone and a build id per arm would be
// a sequenced dispatch per arm. Every driver in this directory therefore rides
// that one id and supplies its own `:init-fn` and `:output-dir` through
// `--config-merge`. That design is good and this file does not change it.
//
// What it costs is stated here once: **one build id means one build cache**,
// and shadow-cljs derives the cache directory from the build id alone —
// `<cache-root>/builds/<build-id>/<mode>` (`shadow.cljs.devtools.config/
// make-cache-dir`), fixed in `new-build` BEFORE any `--config-merge` data is
// applied (`shadow.build/configure`). So the arm is invisible to the cache
// key, and N different programs share one cache entry.
//
// ## The fault that makes this a rule rather than a note
//
// Running one arm after another left a cache that COMPILED CLEANLY and then
// produced an `:advanced` bundle that DIED ON ITS FIRST EXECUTION:
//
//     pageerror: Cannot read properties of undefined (reading 'd')
//
// MEASURED, at unmodified main `6509d8e5c5`: from a cold cache, two
// `run.cjs` builds (`p0-reagent-app`) and one `p0_converge_run.cjs` build
// (`p0-converge-app`), then `hd8_run.cjs` — which exits 1 before taking a
// single sample. `rm -rf .shadow-cljs/builds/hicasso-bench` clears it
// completely.
//
// THE CARRIER IS `shadow-js/`, the npm-conversion cache, and it was isolated
// rather than assumed. `shadow.build.closure/convert-sources-simple` keeps
// `shadow-js/index.json.transit` — the converted-module index plus the build's
// `:injected-libs` — and invalidates it only on `SHADOW-CACHE-KEY` or
// `cache-affecting-options`, NEVER on the set of modules the build actually
// needs. The Reagent arms pull 14 npm modules and no `react/jsx-runtime`; the
// UIx arm needs it. Building the UIx arm over the Reagent arms' index links
// the newcomer against whole-build state established for a different module
// set, and the bundle is wrong in a way only execution can show.
//
// The isolation, each trial from a COLD cache and each replaying the same
// poisoning history (the trap is self-healing once the index has accumulated
// every arm's modules, so a warm trial proves nothing):
//
//     control, remove nothing   -> DEAD  (reading 'd')
//     remove shadow-js/ only    -> ALIVE
//
// The two Closure name-stability maps were the first suspect and are NOT the
// carrier: removing `closure.property.map` and `closure.variable.map` from a
// poisoned cache leaves it dead. That failed guess is why this file clears the
// WHOLE entry instead of the one directory now known to carry it — the
// invariant "N programs, one id, so nothing may cache between them" holds
// whatever shadow-cljs caches next, and a surgical clear is a standing bet on
// internals that has already been lost once.
//
// ## What it costs, measured, because that was the objection
//
// Nothing that registers. Rebuilding `hd8-app` on this box, alternating:
// warm 34s, 26s; cleared 33s, 37s. The clear is inside the run-to-run noise,
// because the time is JVM start, classpath and the Closure `:advanced` pass —
// not the CLJS compile the cache holds. Against a driver run measured in
// minutes it is not a cost at all.
//
// Rejected, with reasons, so the next reader does not re-litigate them:
//
//   * A build id per arm. Six hot-zone, sequenced edits to
//     `implementation/shadow-cljs.edn` to buy back three seconds, and it
//     discards the one thing HD-017 built this lane to avoid.
//   * A cache root per arm (`SHADOW_CLJS='{:cache-root ...}'`, the only lever
//     that reaches `:cache-root`, since `--config-merge` cannot). It would
//     keep a warm cache per arm — but it also duplicates the 22 MB
//     `jar-manifest` and the classpath cache per arm, and the node side still
//     discovers a server through `.shadow-cljs`, so the override can silently
//     fail to apply. A silent non-fix for the silent-failure bug.
//   * Only improving the error message (the bead's fallback). It leaves the
//     trap armed.

const fs = require('node:fs');
const path = require('node:path');

// Clears the shared build cache entry for `buildId`, both modes. Returns the
// directory if one was there, else null. `maxRetries` because this is Windows
// and a scanner or a just-exited JVM can still hold a handle for a moment —
// the same guard `package.json`'s `clean:freehand-*` scripts use.
function resetLaneBuildCache(implDir, buildId) {
  const dir = path.join(implDir, '.shadow-cljs', 'builds', buildId);
  if (!fs.existsSync(dir)) return null;
  fs.rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  return dir;
}

module.exports = { resetLaneBuildCache };
