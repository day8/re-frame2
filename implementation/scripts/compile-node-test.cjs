#!/usr/bin/env node
'use strict';
// rf2-6t03c — a compile of a `:node-test`-family build that ABORTS (nonzero
// exit; e.g. `aborted par-compile, ... still waiting for ...` from
// `shadow.build.compiler/par-compile-one`'s 60s `:par-timeout`, seen under
// box load AND forceable deterministically by overriding that timeout)
// throws before shadow-cljs ever reaches the link/write step for its
// `:output-to` bundle. Left alone, the PREVIOUS successful bundle just sits
// there, byte-identical to a fresh one from the outside. A caller that does
// not chain the compile's exit code — a two-step invocation, a background
// job whose failure is swallowed, anything that isn't `compile && run` —
// silently executes stale compiled code and reports a green that means
// nothing. That is exactly what happened measuring rf2-hofhx: one run was
// discarded because of it.
//
// MEASURED (2026-08-03, this bead): a `--config-merge '{:build-options
// {:par-timeout 1}}'` compile of `node-test` reliably aborts (exit 1) inside
// ~90s, and `out/node-test.js` from the prior successful compile is left
// completely unchanged on disk — same size, same mtime. A subsequent full
// compile with the default timeout self-heals (shadow-cljs's per-namespace
// cache is keyed by source hash, not by the aborted run), so the on-disk
// CACHE was not the reliably-reproducible carrier here; the STALE, still-
// present BUNDLE is. This script closes that gap unconditionally, for any
// cause of abort, not only `--config-merge`.
//
// THE FIX, two parts:
//
//   1. Delete `:output-to` BEFORE compiling, always. A failed compile then
//      leaves NO bundle rather than a stale one: `node out/<build>.js`
//      fails LOUD ("Cannot find module") instead of silently succeeding
//      against old code, regardless of whether the calling script chained
//      the exit code correctly. This makes the dangerous consequence
//      (silent stale-bundle reuse) IMPOSSIBLE by construction rather than
//      relying on every present and future caller to remember `&&`.
//
//   2. When invoked with `--config-merge`, ALSO clear the build's on-disk
//      shadow-cljs cache directory before AND after compiling — the other
//      fix shadow-cljs.edn's `:node-test` build comment names, and the same
//      rule `lane_cache.cjs` already enforces for the hicasso bench lane:
//      one build id driven with N different configs shares ONE cache entry,
//      so a focused/config-merged compile against a SHARED id (like
//      `:node-test`, which the always-on full compile also drives) must
//      never leave mixed-config state behind for the next, unrelated
//      invocation. Isolating the focused run this way costs one JVM-classpath
//      rescan (~seconds, per lane_cache.cjs's own measurement) and buys
//      determinism: the shared id can never carry residue from a config it
//      did not itself request.
//
// The cheapest fix of all remains: don't `--config-merge` against `:node-test`
// in the first place. `node out/node-test.js --test=<ns>[,<ns>...]` selects
// namespaces at RUNTIME, needs no recompile, and cannot poison anything —
// see the comment on shadow-cljs.edn's `:node-test` build.
//
// Usage: node scripts/compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const IMPL_DIR = path.resolve(__dirname, '..');

function main(argv) {
  const [buildId, outputTo, ...extraArgs] = argv;
  if (!buildId || !outputTo) {
    console.error(
      'usage: compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]'
    );
    return 2;
  }

  const usesConfigMerge = extraArgs.some((a) => a === '--config-merge');
  const outputPath = path.resolve(IMPL_DIR, outputTo);

  // Part 2: isolate a focused/config-merged compile from the shared id's
  // cache — before AND after, so the shared id is guaranteed clean for the
  // next caller regardless of whether THIS one succeeds.
  let resetLaneBuildCache = null;
  if (usesConfigMerge) {
    ({ resetLaneBuildCache } = require(
      '../freehand/test/re_frame/freehand/bench/lane_cache.cjs'
    ));
    resetLaneBuildCache(IMPL_DIR, buildId);
  }

  // Part 1: never leave a stale bundle sitting where a fresh one belongs.
  if (fs.existsSync(outputPath)) {
    fs.rmSync(outputPath, { force: true, maxRetries: 5, retryDelay: 100 });
  }

  // Resolve shadow-cljs's own bin entry-point (a plain Node script) and
  // spawn it directly under THIS node binary — never `npx`/`npx.cmd` under
  // a shell. Mirrors serve-and-run-browser-tests.cjs's http-server
  // resolution (rf2-wn4o1): a workspace-local `.cmd` can hijack a
  // `shell:true` launch on Windows, and `.cmd` under `shell:false` fails
  // with EINVAL (the CVE-2024-27980 mitigation). Resolving the actual
  // `.js` entry-point sidesteps both.
  let shadowCljsBin;
  try {
    shadowCljsBin = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_DIR] });
  } catch (err) {
    console.error(`compile-node-test: could not resolve shadow-cljs: ${err.message}`);
    return 1;
  }
  const result = spawnSync(
    process.execPath,
    [shadowCljsBin, 'compile', buildId, ...extraArgs],
    { stdio: 'inherit', cwd: IMPL_DIR }
  );

  if (usesConfigMerge) {
    resetLaneBuildCache(IMPL_DIR, buildId);
  }

  if (result.error) {
    console.error(`compile-node-test: failed to spawn shadow-cljs: ${result.error.message}`);
    return 1;
  }

  if (result.status !== 0) {
    console.error(
      `compile-node-test: shadow-cljs compile ${buildId} did not complete (exit ${result.status}); ` +
        `${outputTo} was cleared before the attempt, not left stale.`
    );
    return result.status;
  }

  if (!fs.existsSync(outputPath)) {
    console.error(
      `compile-node-test: shadow-cljs compile ${buildId} exited 0 but ${outputTo} is missing — treating as fatal.`
    );
    return 1;
  }

  return 0;
}

if (require.main === module) {
  process.exit(main(process.argv.slice(2)));
}

module.exports = { main };
