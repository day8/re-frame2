'use strict';
// THE HICASSO LANE'S ONE BUILD DOOR — rf2-2rtt6.73.
//
// Every driver in this lane compiled through a hand-rolled copy of the same
// eight lines, and every copy carried the same hole:
//
//     const r = spawnSync(node, [runner, 'release', BUILD_ID, ...], {
//       cwd: IMPL, stdio: ['ignore', 'inherit', 'inherit'] });
//     if (r.status !== 0) { ...; process.exit(1); }
//
// **shadow-cljs exits 0 when a build emits warnings.** So the only thing
// those eleven drivers ever checked was the one condition that does not
// happen. MEASURED on main, by renaming `M-NO-PROPS`'s def in
// `walk_profile_app.cljs` and leaving its two use sites alone:
//
//     [:hicasso-bench] Build completed. (186 files, 131 compiled, 2 warnings, 37.55s)
//     ------ WARNING #1 - :undeclared-var ------
//      Use of undeclared Var re-frame.bench.hicasso.walk-profile-app/M-NO-PROPS
//     ...
//     [hicasso] ok
//     BEFORE_DRIVER_EXIT=0
//
// Two undeclared vars, a full run, a printed table of numbers, exit 0. Under
// `:advanced` an undeclared var is `undefined` at the call site, so an arm can
// silently become a DIFFERENT ARM and still publish a plausible figure — and a
// worker mutation-proving a change through this lane gets a proof worth
// nothing, which is how the hole was found.
//
// This module is the lane's only spawn of shadow-cljs. It captures the child's
// output instead of inheriting the stream, echoes every byte of it, and then
// refuses the build unless the output says what a clean build says.
//
// ## What it refuses, and why each one is here
//
//   1. A non-zero child exit — the hard errors the old code already caught.
//   2. `warnings > 0` in ANY parsed `Build completed.` summary. The teeth.
//   3. NO parsable summary at all. A gate whose parser recovered nothing must
//      not report success: that is the same fail-open one level up, and it is
//      the exact false-green `check-examples-compile.cjs` had to close after
//      the fact (rf2-nlnd9y.1). If shadow's summary format moves, this lane
//      goes RED and someone fixes the parser — it does not go quietly green.
//   4. A `------ WARNING` marker in the output with every parsed summary
//      reading zero. That combination means the count was read from a line
//      that is no longer the line carrying the truth.
//
// ## What it deliberately does NOT do
//
// It does not lower `:warnings` or touch `:infer-externs` to make anything
// pass. Silencing a class to keep a gate green would rebuild the defect this
// module exists to remove.
//
// ## The one cost, stated
//
// `stdio: 'pipe'` means shadow's progress is printed when the build FINISHES
// rather than as it goes — a ~35s silence where there used to be a trickle.
// Every driver prints its own "building ..." line first, and buffering is what
// makes the output readable at all; `spawnSync` cannot both inherit and
// capture. Nothing is dropped: stdout and stderr are echoed in full, ahead of
// any verdict this module prints.

const { spawnSync } = require('node:child_process');
const path = require('node:path');

// shadow-cljs colours its output, so every pattern here reads ANSI-stripped
// text. The lane's build id is a BARE keyword (`[:hicasso-bench]`), unlike the
// slash-bearing `[:examples/login-uix]` ids `check-examples-compile.cjs`
// parses — hence a looser bracket match rather than a reuse of that regex.
const ANSI_RE = /\x1B\[[0-9;]*m/g;
const COMPLETED_RE = /\[(:[^\]\s]+)\]\s+Build completed\.[^\n]*?(\d+)\s+warnings?/g;
const FAILED_RE = /\[(:[^\]\s]+)\]\s+Build failed/g;

// `------ WARNING #1 - :undeclared-var ------`, the block header shadow prints
// per warning. Used as the corroborating signal for refusal (4) above.
const WARNING_MARKER_RE = /-{2,}\s*WARNING\b/;
const WARNING_HEADLINE_RE = /-{2,}\s*(WARNING\s*#\d+[^-\n]*?)\s*-{2,}/g;

function stripAnsi(s) {
  return String(s).replace(ANSI_RE, '');
}

/** Every `Build completed.` summary in `output`, with its warning count. */
function parseBuildSummaries(output) {
  const src = stripAnsi(output);
  const completed = [];
  const failed = [];
  let m;
  COMPLETED_RE.lastIndex = 0;
  while ((m = COMPLETED_RE.exec(src)) !== null) {
    completed.push({ build: m[1], warnings: Number(m[2]) });
  }
  FAILED_RE.lastIndex = 0;
  while ((m = FAILED_RE.exec(src)) !== null) {
    failed.push(m[1]);
  }
  return { completed, failed };
}

/** The `WARNING #N - :type` headlines, for naming what went wrong. */
function warningHeadlines(output) {
  const src = stripAnsi(output);
  const out = [];
  let m;
  WARNING_HEADLINE_RE.lastIndex = 0;
  while ((m = WARNING_HEADLINE_RE.exec(src)) !== null) out.push(m[1].trim());
  return out;
}

/**
 * Decide a build from shadow's exit status and its captured output. Pure, so
 * `lane_build.test.cjs` can prove each refusal fires without spawning a JVM.
 *
 * @returns {{ok: true} | {ok: false, reason: string, detail: string[]}}
 */
function judgeBuild({ status, output }) {
  const { completed, failed } = parseBuildSummaries(output);

  if (status !== 0) {
    return {
      ok: false,
      reason: `shadow-cljs exited ${status}`,
      detail:
        failed.length > 0
          ? [`failed build(s): ${failed.join(', ')}`]
          : ['see the compiler output above'],
    };
  }

  // (3) A parser that recovered nothing has verified nothing.
  if (completed.length === 0) {
    return {
      ok: false,
      reason:
        'shadow-cljs exited 0 but NO parsable "Build completed." summary was ' +
        'found in its output',
      detail: [
        'the warning count is read from that line, so its absence means this',
        'build was NOT checked for warnings — refusing to pass it green.',
        'If shadow-cljs changed its summary format, fix COMPLETED_RE in',
        'lane_build.cjs rather than removing this refusal.',
      ],
    };
  }

  // (2) The teeth.
  const warned = completed.filter((b) => b.warnings > 0);
  if (warned.length > 0) {
    const headlines = warningHeadlines(output);
    return {
      ok: false,
      reason: warned
        .map((b) => `${b.build} compiled with ${b.warnings} warning(s)`)
        .join('; '),
      detail: [
        ...(headlines.length > 0 ? headlines.map((h) => `  ${h}`) : []),
        'A warning is a FAILURE in this lane. Under :advanced an undeclared',
        'var is `undefined` at the call site, so a warned build can measure a',
        'different program than the one it names. Fix the source; do not',
        'lower the warning.',
      ],
    };
  }

  // (4) Warning evidence the summary did not account for.
  if (WARNING_MARKER_RE.test(stripAnsi(output))) {
    return {
      ok: false,
      reason:
        'a "------ WARNING" block appears in the compiler output but every ' +
        'parsed summary reads 0 warnings',
      detail: [
        'the summary parser has drifted past real warning evidence — exactly',
        'the false-green this door exists to prevent.',
      ],
    };
  }

  return { ok: true };
}

/**
 * Build `buildId` through shadow-cljs, echo everything it printed, and RETURN
 * the verdict. Use this when the caller builds several arms and wants to
 * report them all (`jsfb_build.cjs`); use `shadowBuild` when the first bad
 * build should end the process, which is every other driver.
 *
 * `mode` is `'release'` or `'compile'`. `configMerge` MUST be a single line —
 * shadow-cljs's CLI re-splits `--config-merge` on whitespace once the EDN
 * contains a newline and then reports `EOF while reading` from a fragment.
 *
 * Spawn form is the hardened one used across the repo: shadow's own JS
 * entry-point under THIS node binary, `shell:false`, never a `.cmd` shim.
 */
function shadowBuildVerdict({ impl, mode, buildId, configMerge }) {
  const runner = path.join(impl, 'node_modules', 'shadow-cljs', 'cli', 'runner.js');
  const args = [runner, mode, buildId];
  if (configMerge) args.push('--config-merge', configMerge);

  const r = spawnSync(process.execPath, args, {
    cwd: impl,
    encoding: 'utf8',
    // Generous: a warned build prints ~16 lines of context per warning, and a
    // truncated capture would be a parse failure dressed as a clean build.
    maxBuffer: 256 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const output = `${r.stdout || ''}${r.stderr || ''}`;
  // Echo FIRST and in full, before any verdict — a reader must be able to see
  // what the judgement was made from.
  if (output) process.stderr.write(output);

  if (r.error) {
    return {
      ok: false,
      reason: `could not run shadow-cljs: ${r.error.message}`,
      detail: [],
    };
  }

  return judgeBuild({ status: r.status, output });
}

/**
 * `shadowBuildVerdict`, but a bad verdict ends the process. Returns normally
 * only on a clean build, so callers keep reading top-to-bottom.
 */
function shadowBuild({ impl, mode, buildId, configMerge, tag }) {
  const verdict = shadowBuildVerdict({ impl, mode, buildId, configMerge });
  if (!verdict.ok) {
    reportRefusal(tag, verdict);
    process.exit(1);
  }
}

/** The one refusal format, so every driver in the lane fails the same way. */
function reportRefusal(tag, verdict) {
  console.error(`\n[${tag}] BUILD REFUSED — ${verdict.reason}`);
  for (const line of verdict.detail) console.error(`[${tag}] ${line}`);
}

module.exports = {
  shadowBuild,
  shadowBuildVerdict,
  reportRefusal,
  judgeBuild,
  parseBuildSummaries,
  warningHeadlines,
  stripAnsi,
};
