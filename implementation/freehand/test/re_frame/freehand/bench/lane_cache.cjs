'use strict';
// THE HICASSO LANE'S ONE CACHE RULE — rf2-2rtt6.20.
//
// HD-017 gives the whole P0 lane a SINGLE build id, `:hicasso-bench`, because
// `implementation/shadow-cljs.edn` is hot-zone and a build id per arm would be
// a sequenced dispatch per arm. Every driver in the lane therefore rides
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
// And it buys something beyond not crashing: **the build becomes
// deterministic.** Two cleared-cache builds of the same arm emit a
// byte-identical `main.js` (sha256 `a1d14753ef818fcd…`, measured both ways),
// where before the fix the bundle depended on which arm had built last —
// Closure's renaming for a build compiled fresh differs from the same build
// compiled with a sibling warm, which `hicasso_narrow_run.cjs` measured at
// 4,075 bytes. A reproduction command a studio page publishes should not emit
// a different bundle depending on what the reader ran an hour ago.
//
// ## What the rows published BEFORE the clear are worth — rf2-t84ee
//
// The `hicasso-bench` lane got this call on 2026-07-31. The seven
// `freehand-release` riders did not get it until `448d368bb9` on 2026-08-06,
// so every row those seven have published was measured without it. Whether any
// one run was warm over a SIBLING is operator shell history and cannot be
// recovered. Whether the fault could have reached that row CAN be, and was:
// each arm built once from a cleared cache and once with a sibling warm ahead
// of it, at `5a08b14a29`, then loaded in headless Chromium.
//
//   arm             cold           warm over a sibling            loads?
//   reads_ladder     649,251 B      649,251 B  (spine_ablation)    yes
//                                   649,140 B  (b6_prod, b7)       NO
//   spine_ablation   655,896 B      655,896 B  (reads_ladder)      yes
//                                   655,770 B  (b6_prod, b7, …)    NO
//   b7               824,837 B      824,837 B  (b6_prod)           yes
//   b6_prod          841,215 B      841,215 B  (b7)                yes
//   b8               813,777 B      813,572 B                      yes
//   b10_prod         786,778 B      786,591 B                      yes
//   b6_profile     3,413,752 B    3,411,573 B                      yes
//
// EVERY arm's bundle changes, and BOTH cases are real — which one you get
// depends on what ran before, not on which arm you are. The loud one is
// self-limiting BY CONSTRUCTION rather than by luck: every driver in this lane
// waits on its own `*_READY`/`*_DONE` global raced against `sentinel.cjs`, and
// a bundle that raises `Cannot read properties of undefined (reading 'd')`
// before its entry runs sets no global at all, so it cannot reach a table.
//
// The quiet one is Closure renaming and nothing else — `b7`'s two bundles are
// the same LENGTH, 4.03% of their bytes differ, and the first divergence is a
// variable name in the `closure_uid_` preamble. **It does not move a reading.**
// `reads_ladder_run.cjs` and `b7_run.cjs --only heap`, each run unchanged over
// both of its own bundles, same arms, same rounds, same controls, exit 0 and
// zero unverified mounts on all four:
//
//   ladder, Reagent      B/read 947 -> 948   R=0 shell 431 -> 430
//   b7, reader A         worst arm moves 1 B/boundary of 244..4,303 (0.15%)
//
// Both are inside the instruments' own round-to-round ranges, so a renaming
// delta is not something a retained-heap row can see.
//
// THE SAME A/B ON THE CLOCK IS NOT EVIDENCE EITHER WAY, and is reported rather
// than dropped. `b6_prod_run.cjs` over its two bundles exits 0 both times and
// passes its parity gate both times, but two bundles mean two runs and this
// studio's clock method interleaves arms WITHIN a round precisely because a
// loaded box drifts between them. It duly did: the second run's interpreted W1
// reads `[2.68-10.21]` against the first's `[2.75-4.32]`, so the means part
// company while every range overlaps. A clock A/B across runs cannot separate
// an artefact from the box, and nothing above rests on one.
//
// AND THE CONTROL THAT MAKES THAT LEGIBLE: a build warm over ITSELF is
// byte-identical — the ladder built twice with no clear between compiles 0 the
// second time and emits the same sha256. "Warm" is not the fault; "warm over a
// SIBLING" is. A driver invoked twice in a row — which is how the ladder took
// its two substrate pages — was never at risk.
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
