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
// rf2-4a6ei — A COMPILE THAT SUCCEEDS WITH WARNINGS IS NOT A GREEN LANE, and
// until this script read the tally it was treated as one.  The bead reports
// prose inside a test namespace — a citation with a bare `"` in a docstring —
// surviving a 153-check three-engine browser gate, and explains it by the
// namespace failing to compile and so dropping out of the build.
//
// MEASURED ON THIS TREE, and the explanation is REFUTED.  The plant was made
// twice in `security/test/re_frame/security/ssr_escaping_security_cljs_test.cljc`
// and `npm run test:security` run over each:
//
//   * inside the NS DOCSTRING — the ns form does not read, and the build FAILS,
//     exit 1, naming the file and the line.  Caught already, by this script's
//     existing exit-status check.
//   * inside a DEFTEST DOCSTRING, one form further down — the bare quote closes
//     the string early and reopens it before the line ends, so the file still
//     READS.  `one frame per app` becomes four bare symbols in the test body,
//     which compile to `undefined` in JavaScript and evaluate harmlessly.  The
//     build completes, the suite runs, and the numbers are IDENTICAL to the
//     clean tree: `Ran 93 tests containing 705 assertions. / 0 failures, 0
//     errors.`, exit 0.  The one thing that moved is the tally: `0 warnings`
//     became `4 warnings`, all of them `Use of undeclared Var`.
//
// So the namespace never left the build, the test count never dropped, and the
// bead's candidate repair — fail when the selector matches fewer namespaces
// than expected — would not have caught this: nothing was missing.  What was
// missing was a READER for the number shadow-cljs had already printed.
//
// THE TALLY IS THE GATE, and it needs no bookkeeping to stay honest.  Every
// `:node-test`-family lane compiles warning-free today, measured before arming
// this: node-test 2395 files, node-test-security 216, node-test-testbed-support
// 693, node-test-ui 343, node-test-freehand 488, node-test-hicasso 457,
// node-test-perf-nightly 159 — 0 warnings in all seven.  A floor of zero is
// therefore the bound that cannot go stale, in the same spirit as
// `RF2_MIN_TESTS`'s default of 1, and it carries no knob: a warning in a test
// build is a defect, and an env var to permit one would be this bug wearing a
// hat.
//
// THE SAME RULE ALREADY EXISTS ONE LANE OVER, which is the strongest evidence
// that this is the right shape and not an invention.  `check-examples-compile.
// cjs` parses the identical shadow-cljs line for the `:examples/*` builds, reds
// on `warnings > 0`, and — independently of this, under rf2-nlnd9y.1 — reached
// the same conclusion about the unreadable case: a summary that never appeared
// or no longer matches is a FAILURE, because otherwise the gate "reported
// SUCCESS having verified nothing about that build".
//
// NOT SHARED, and the reason is at source rather than laziness.  That parser is
// anchored on a build id containing a slash (`[:examples/login-helix]`), which
// no `:node-test`-family id carries, so it matches nothing here and could not be
// called as it stands.  Generalising it would mean editing the examples gate to
// serve this one; two four-line readers in the lanes that own them is the
// smaller thing, and the divergence is deliberate where they differ: that gate
// treats a singular `1 warning` as UNPARSEABLE and fails, this one reads it and
// fails NAMING the count.  Both red; this one says why.
//
// THERE ARE THREE OF US, NOT TWO — and this is the one place that says so
// (rf2-040s1).  The paragraph above used to close by promising that a third lane
// wanting this line would be the moment to mint one reader for all three.  The
// third lane already existed when that was written: `lane_build.cjs` has read
// the line since 2026-08-03, cites the same examples gate, and gives the same
// slash-anchor reason for not reusing it.  Two authors hit one wall eleven days
// apart and neither found the other, which is the defect the count was meant to
// catch and did not.  So the roster is stated, and the other two files point
// here rather than restate it:
//
//     implementation/scripts/check-examples-compile.cjs   :examples/* + :testbeds/*
//     implementation/scripts/compile-node-test.cjs        :node-test-family (this)
//     bench/hicasso/src/re_frame/bench/hicasso/lane_build.cjs  :hicasso-bench (repo root)
//
// THE COUNT WAS STILL THE WRONG TRIGGER, so it is replaced rather than
// incremented.  MEASURED at three, against the live parsers: no single pattern
// serves all three as they stand — the examples regex matches ZERO bare-keyword
// ids, and this one reads a summary carrying no `[:id]` bracket at all, which
// that lane's regex cannot.  Nor is the examples slash merely a capture: it is a
// FILTER, and that gate fails on any summary whose id was not requested, so an
// id-agnostic shared pattern would hand it every id shadow prints and re-open a
// settled question inside a gate over 54 builds.  What actually differs between
// the three is not the regex but the SELECTION POLICY over the rows it yields:
// that gate reconciles a requested SET, `lane_build.cjs` requires every row to
// read zero, and this one takes the LAST row so a dependency's summary is never
// mistaken for the lane's.  A shared module would remove three regexes and leave
// three policies.
//
// SO THE TEST FOR THE NEXT LANE IS ITS POLICY, NOT THE HEADCOUNT.  A lane whose
// selection policy one of the three already implements should call that reader's
// export — all three export their parser — instead of writing a fourth.  A lane
// with a genuinely new policy writes its own four lines and adds itself above.
// Duplication is affordable here precisely because the shared property cannot
// fail quietly: all three refuse an unreadable summary, so a shadow-cljs
// reformat produces three RED gates and one afternoon's work, never a pass.
//
// NOT A NAG, deliberately.  shadow-cljs already prints its own `Build
// completed. (N files, M compiled, W warnings, Ts)` line on every run, so the
// reach is on the page whether this passes or fails and there is nothing to
// restate.  What was absent was not the number but any consequence attached to
// it.  This script therefore says nothing extra on a clean compile and speaks
// only when the tally is non-zero or unreadable.
//
// Usage: node scripts/compile-node-test.cjs <build-id> <output-to> [extra shadow-cljs args...]
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const IMPL_DIR = path.resolve(__dirname, '..');

// shadow-cljs colours its output, and a colour reset can land between the
// number and its noun.
const ANSI = /\[[0-9;]*m/g;

// The tally line, read from the WHOLE captured output rather than a chunk, so a
// line split across two reads still matches.  The LAST match wins: one
// invocation compiles one build id, and anything earlier belongs to a
// dependency's own build.
const BUILD_COMPLETED =
  /Build completed\.\s*\(\s*(\d+)\s+files?,\s*(\d+)\s+compiled,\s*(\d+)\s+warnings?,/g;

function buildTally(text) {
  let last = null;
  for (const match of text.replace(ANSI, '').matchAll(BUILD_COMPLETED)) last = match;
  if (!last) return null;
  return {
    files: Number(last[1]),
    compiled: Number(last[2]),
    warnings: Number(last[3]),
  };
}

// Run shadow-cljs, streaming its output through UNCHANGED while capturing it.
// The stream has to stay live — a lane that compiles for three minutes in
// silence is a worse tool than one that reports nothing — so this is `spawn`
// with a tee rather than `spawnSync` with a pipe, which would withhold every
// line until the build ended.
function runCapturing(command, args, options) {
  return new Promise((resolve) => {
    const child = spawn(command, args, {
      ...options,
      stdio: ['inherit', 'pipe', 'pipe'],
    });
    let captured = '';
    for (const [stream, sink] of [
      [child.stdout, process.stdout],
      [child.stderr, process.stderr],
    ]) {
      stream.on('data', (chunk) => {
        captured += chunk.toString();
        sink.write(chunk);
      });
    }
    child.on('error', (error) => resolve({ error, captured }));
    // BOTH arguments. `close` reports a signal death as (null, 'SIGTERM') —
    // the status is NULL, and the signal name is the only place the cause is
    // written down. Dropping the second argument threw that away and left the
    // caller a status it cannot tell apart from "no idea" (rf2-i7q4).
    child.on('close', (status, signal) => resolve({ status, signal, captured }));
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
  const result = await runCapturing(
    process.execPath,
    [shadowCljsBin, 'compile', buildId, ...extraArgs],
    { cwd: IMPL_DIR }
  );

  if (usesConfigMerge) {
    resetLaneBuildCache(IMPL_DIR, buildId);
  }

  if (result.error) {
    console.error(`compile-node-test: failed to spawn shadow-cljs: ${result.error.message}`);
    return 1;
  }

  // rf2-i7q4 — A SIGNAL-KILLED CHILD IS NOT A PASSING BUILD, and Node's own
  // convention is what made it read as one. `close` reports a signal death as
  // (null, 'SIGTERM'): the status is NULL rather than a number, `null !== 0`
  // so this branch was entered and printed "did not complete (exit null)" —
  // and then returned that null to `process.exit()`, which reads a non-number
  // as SUCCESS. The wrapper said the compile had failed and told automation it
  // had passed, in the same breath. An OOM kill, a CI job cancellation, or an
  // administrative taskkill of the shadow-cljs JVM all land here.
  //
  // So the seam is: a NUMERIC status is the child's own verdict and passes
  // through untouched (0 continues into the output/tally checks below; 1, 3,
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

  // rf2-4a6ei. Everything above asks whether the compile RAN; this asks what it
  // found. See the header for the measurement: a broken string literal one form
  // below the ns form leaves a lane that compiles, runs, and reports test counts
  // identical to the clean tree — the only moving number is this one.
  const tally = buildTally(result.captured);
  if (!tally) {
    console.error(
      `compile-node-test: shadow-cljs compile ${buildId} exited 0 and wrote ` +
        `${outputTo}, but printed no "Build completed." tally, so the warning ` +
        `count for this lane is UNKNOWN. Refusing rather than reporting a green ` +
        `for a question that was never answered — a warning here is how a broken ` +
        `docstring reaches a suite that still counts every test (rf2-4a6ei).`
    );
    return 1;
  }
  if (tally.warnings > 0) {
    console.error(
      `\ncompile-node-test: ${buildId} compiled ${tally.compiled} of ` +
        `${tally.files} files with ${tally.warnings} WARNING(S). The warnings ` +
        `are printed above; every :node-test-family lane compiles warning-free, ` +
        `so this is a regression rather than a backlog.\n` +
        `  This is a gate because a test suite can be GREEN and wrong: a bare ` +
        `double-quote inside a deftest docstring closes the string early, the ` +
        `words after it become bare symbols, and in JavaScript those compile to ` +
        `\`undefined\` and evaluate harmlessly. The suite then reports the same ` +
        `test and assertion counts as the clean tree while the docstring it was ` +
        `meant to carry is gone. "Use of undeclared Var" in a test namespace is ` +
        `that shape (rf2-4a6ei).\n`
    );
    return 1;
  }

  return 0;
}

if (require.main === module) {
  main(process.argv.slice(2)).then((code) => process.exit(code));
}

module.exports = { main, buildTally };
