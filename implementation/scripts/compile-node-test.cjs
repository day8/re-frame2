#!/usr/bin/env node
'use strict';
// A compile of a `:node-test`-family build that ABORTS (nonzero
// exit; e.g. `aborted par-compile, ... still waiting for ...` from
// `shadow.build.compiler/par-compile-one`'s 60s `:par-timeout`, seen under
// box load AND forceable deterministically by overriding that timeout)
// throws before shadow-cljs ever reaches the link/write step for its
// `:output-to` bundle. Left alone, the PREVIOUS successful bundle just sits
// there, byte-identical to a fresh one from the outside. A caller that does
// not chain the compile's exit code — a two-step invocation, a background
// job whose failure is swallowed, anything that isn't `compile && run` —
// silently executes stale compiled code and reports a green that means
// nothing.
//
// A `--config-merge '{:build-options {:par-timeout 1}}'` compile of
// `node-test` reliably aborts (exit 1) inside ~90s, and `out/node-test.js`
// from the prior successful compile is left completely unchanged on disk —
// same size, same mtime. A subsequent full compile with the default timeout
// self-heals (shadow-cljs's per-namespace cache is keyed by source hash, not
// by the aborted run), so the on-disk CACHE is not the reliably-reproducible
// carrier; the STALE, still-present BUNDLE is. This script closes that gap
// unconditionally, for any cause of abort, not only `--config-merge`.
//
// Two parts:
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
//      remedy shadow-cljs.edn's `:node-test` build comment names, and the same
//      rule `lane_cache.cjs` enforces for the fresco bench lane:
//      one build id driven with N different configs shares ONE cache entry,
//      so a focused/config-merged compile against a SHARED id (like
//      `:node-test`, which the always-on full compile also drives) must
//      never leave mixed-config state behind for the next, unrelated
//      invocation. Isolating the focused run this way costs one JVM-classpath
//      rescan (~seconds, per lane_cache.cjs's own measurement) and buys
//      determinism: the shared id can never carry residue from a config it
//      did not itself request.
//
//      The focused build's RUNTIME is kept out of that directory. A dev-mode
//      node bundle loads its `cljs-runtime` files at RUN time from the
//      build's `:output-dir`, which shadow-cljs defaults to `<mode>/out`
//      INSIDE the cleared entry, so the clear after compiling would delete
//      exactly what the bundle is about to load. A config-merged compile
//      therefore writes its runtime to `<output-to stem>.config-merge/`
//      beside its own bundle, a directory the shared id's full compile never
//      reads, and the whole entry is still cleared. Clearing everything BUT
//      `<mode>/out` would also keep the bundle runnable, but only as a bet on
//      shadow-cljs's cache layout, which is the bet `lane_cache.cjs` declines
//      by clearing the whole entry.
//
// The cheapest remedy of all: don't `--config-merge` against `:node-test`
// in the first place. `node out/node-test.js --test=<ns>[,<ns>...]` selects
// namespaces at RUNTIME, needs no recompile, and cannot poison anything —
// see the comment on shadow-cljs.edn's `:node-test` build.
//
// WARNINGS ARE FATAL IN THE BUILD CONFIG, NOT HERE. shadow-cljs.edn's
// `:target-defaults` sets `:warnings-as-errors true` for every `:node-test`
// build, so a warning fails the compile and arrives here as a nonzero exit,
// exactly as it does for a bare `npx shadow-cljs compile <id>`. A test build
// needs that more than most: a bare `"` inside a deftest docstring closes the
// string early, the words after it become undeclared vars that compile to
// `undefined`, and the suite still reports the clean tree's test and assertion
// counts.
//
// Usage: node scripts/compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const IMPL_DIR = path.resolve(__dirname, '..');

// Where a config-merged compile writes its runtime: beside its own bundle, out
// of reach of the cache clear (see Part 2 of the header). Forward slashes, so
// the path reads as an EDN string on every platform.
function configMergeOutputDir(outputPath) {
  const { dir, name } = path.parse(outputPath);
  return path.join(dir, `${name}.config-merge`).split(path.sep).join('/');
}

// Run shadow-cljs with its output going straight to this process's own, so a
// lane that compiles for minutes reports as it goes.
function run(command, args, options) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { ...options, stdio: 'inherit' });
    child.on('error', (error) => resolve({ error }));
    // BOTH arguments. `close` reports a signal death as (null, 'SIGTERM') —
    // the status is NULL, and the signal name is the only place the cause is
    // written down. Dropping the second argument would throw that away and
    // leave the caller a status it cannot tell apart from "no idea".
    child.on('close', (status, signal) => resolve({ status, signal }));
  });
}

async function main(argv) {
  const [buildId, outputTo, ...extraArgs] = argv;
  if (!buildId || !outputTo) {
    console.error(
      'usage: compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]'
    );
    return 2;
  }

  const usesConfigMerge = extraArgs.some((a) => a === '--config-merge');
  const outputPath = path.resolve(IMPL_DIR, outputTo);
  // shadow-cljs deep-merges repeated `--config-merge` values in order, so this
  // one goes FIRST and a caller's own `:output-dir` still wins.
  const runtimeArgs = usesConfigMerge
    ? ['--config-merge', `{:output-dir ${JSON.stringify(configMergeOutputDir(outputPath))}}`]
    : [];

  // Part 2: isolate a focused/config-merged compile from the shared id's
  // cache — before AND after, so the shared id is guaranteed clean for the
  // next caller regardless of whether THIS one succeeds.
  let resetLaneBuildCache = null;
  if (usesConfigMerge) {
    ({ resetLaneBuildCache } = require(
      '../core/test/re_frame/bench/lane_cache.cjs'
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
  // resolution: a workspace-local `.cmd` can hijack a
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
  const result = await run(
    process.execPath,
    [shadowCljsBin, 'compile', buildId, ...runtimeArgs, ...extraArgs],
    { cwd: IMPL_DIR }
  );

  if (usesConfigMerge) {
    resetLaneBuildCache(IMPL_DIR, buildId);
  }

  if (result.error) {
    console.error(`compile-node-test: failed to spawn shadow-cljs: ${result.error.message}`);
    return 1;
  }

  // A SIGNAL-KILLED CHILD IS NOT A PASSING BUILD, and Node's own convention
  // would make it read as one. `close` reports a signal death as
  // (null, 'SIGTERM'): the status is NULL rather than a number, `null !== 0`
  // so this branch is entered — and returning that null to `process.exit()`,
  // which reads a non-number as SUCCESS, would print "did not complete (exit
  // null)" and tell automation the compile had passed, in the same breath. An
  // OOM kill, a CI job cancellation, or an administrative taskkill of the
  // shadow-cljs JVM all land here.
  //
  // So the seam is: a NUMERIC status is the child's own verdict and passes
  // through untouched (0 continues into the output check below; 1, 3,
  // anything else is returned as-is). Any NON-numeric completion is abnormal
  // by construction and normalises to a stable 1.
  if (result.status !== 0) {
    const numeric = typeof result.status === 'number';
    const cause = numeric
      ? `did not complete (exit ${result.status})`
      : result.signal
        ? `was terminated by signal ${result.signal} before it could complete`
        : 'did not complete and reported no exit status';
    console.error(
      `compile-node-test: shadow-cljs compile ${buildId} ${cause}; ` +
        `${outputTo} was cleared before the attempt, not left stale.`
    );
    return numeric ? result.status : 1;
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
  main(process.argv.slice(2)).then((code) => process.exit(code));
}

module.exports = { main };
