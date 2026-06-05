#!/usr/bin/env node
/*
 * `test:examples-compile` — compile-coverage gate over EVERY declared
 * standalone `:examples/*` shadow-cljs build (rf2-0vav5.1 + rf2-cn6kc.1).
 *
 * The gap this closes
 * -------------------
 * Several `:examples/*` builds are declared shadow-cljs browser targets
 * with init-fns but were compiled by NO automated gate:
 *
 *   - :examples/login-uix, :examples/dashboard-uix   (UIx — rf2-0vav5.1)
 *   - :examples/login-helix, :examples/process-monitor-helix
 *                                                    (Helix — rf2-cn6kc.1)
 *
 * Only the counter trio (:examples/counter / -uix / -helix) was covered
 * (release-built by `test:bundle-isolation`), so a namespace / init-fn /
 * dependency / schema / machine / substrate-form regression in any of the
 * others shipped GREEN until a human manually ran the example. This is the
 * compile-layer fix: it does NOT add any per-example `*.spec.cjs` under
 * `examples/` (forbidden by rf2-8cevm) — it just COMPILES every declared
 * standalone example so a compile-time defect fails CI.
 *
 * Auto-covering by construction
 * -----------------------------
 * The build list is DERIVED from `shadow-cljs.edn` (the `:examples/*`
 * build ids) rather than hardcoded, so a NEWLY-declared example build is
 * swept by this gate the moment it lands — no second edit, no drift. This
 * mirrors the `:dev-http` drift-guard approach in `dev-testbed.test.cjs`
 * (rf2-d3fb7.1): shadow-cljs.edn is the single source of truth and is read
 * here ONLY (it is a hot-zone file — never edited by this script).
 *
 * Compile, not release
 * --------------------
 * `shadow-cljs compile` (non-optimized) is enough to catch the regression
 * classes above (missing ns / init-fn / :require / compile-time form
 * errors). We deliberately do NOT `release` — advanced optimization is the
 * job of `test:bundle-isolation` / `test:perf-bundle` for the few builds
 * that need a release-shaped check, and a non-optimized compile is far
 * cheaper. All declared example builds are passed to a SINGLE
 * `shadow-cljs compile` invocation: shadow shares the compilation cache
 * across builds in one process, so the cost is far below N independent
 * compiles, and `compile` (unlike `release`) does no Closure externs
 * prebuild, so there is no shared-externs.zip race (the dev-testbed.cjs
 * note that motivated explicit-build-ids applies to `watch`, not here).
 *
 * WARNINGS ARE FAILURES (teeth)
 * -----------------------------
 * Crucially, `shadow-cljs compile` exits 0 even when a build emits
 * warnings — an `:undeclared-var` from a typo'd init-fn / symbol, a redef,
 * an externs-inference miss — and `:warnings-as-errors` only bites on
 * `release`, not `compile`. So a typo'd init-fn (one of the EXACT named
 * regression classes) would otherwise "Build completed. (… 1 warnings …)"
 * and ship GREEN. This gate therefore captures shadow's output, echoes it
 * live, and FAILS if ANY build reports `> 0 warnings` OR if shadow exits
 * non-zero (a hard error such as a missing `:require`d namespace). All 39
 * example builds compile with ZERO warnings today, so a zero-warning floor
 * is a clean, real-teeth bar — not a noisy one.
 *
 * SPAWN FORM (rf2-wn4o1 / rf2-1ggkn): resolve shadow-cljs's own JS
 * entry-point (`shadow-cljs/cli/runner.js`) and run it under THIS node
 * binary (`process.execPath`) with `shell:false` — never `npx`/`npx.cmd`
 * under a shell. Same hardened posture as story-build.cjs /
 * serve-and-run-xray-feature-gate.cjs; required by the
 * `_script-spawn-policy.test.cjs` source-policy gate.
 *
 * CLI
 * ---
 *   node scripts/check-examples-compile.cjs           # compile all example builds
 *   node scripts/check-examples-compile.cjs --list     # print the derived build list, exit 0
 *
 * The pure enumeration + parser are exported for
 * `check-examples-compile.test.cjs`, which pins the parser (non-vacuous,
 * covers the four previously-uncovered builds) so this gate keeps its
 * teeth.
 */

'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { IMPL_ROOT } = require('./_path-policy.cjs');

// ---------------------------------------------------------------------------
// shadow-cljs.edn enumeration. We hand-roll a focused scan rather than pull
// in an EDN dependency — the only shape we read is top-level build-id keys
// (`:examples/<name> {`). This matches the parser style already used by the
// dev-testbed drift guard (rf2-d3fb7.1).
// ---------------------------------------------------------------------------

/** Read shadow-cljs.edn from the implementation root. */
function readShadowEdn() {
  return fs.readFileSync(path.join(IMPL_ROOT, 'shadow-cljs.edn'), 'utf8');
}

/**
 * Strip line comments (`;` to end-of-line) so a commented-out build id
 * (e.g. the removed `:examples/xray-rhs-smoke` note) can't be mistaken for
 * a live build. Naive but sufficient: the build-id keys we scan never
 * appear inside string literals in shadow-cljs.edn.
 */
function stripEdnComments(edn) {
  return edn
    .split('\n')
    .map((line) => {
      const i = line.indexOf(';');
      return i === -1 ? line : line.slice(0, i);
    })
    .join('\n');
}

/**
 * Enumerate every declared standalone `:examples/*` build id, in sorted
 * order. A build def is a top-level `:examples/<name> {` key. Returns the
 * colon-stripped coords shadow-cljs's CLI expects (e.g.
 * `examples/login-uix`), de-duplicated and sorted for a stable gate list.
 *
 * The keys are matched at line-start indentation (two spaces) followed by
 * `:examples/<name>` and an opening brace, mirroring how build defs are
 * written throughout shadow-cljs.edn — so a `:examples/...` token that
 * appears mid-line (in prose or a nested map) is never picked up as a build.
 */
function enumerateExampleBuilds(edn) {
  const src = stripEdnComments(edn);
  const re = /^\s*:(examples\/[\w.-]+)\s*\{/gm;
  const found = new Set();
  let m;
  while ((m = re.exec(src)) !== null) {
    found.add(m[1]);
  }
  return [...found].sort();
}

// ---------------------------------------------------------------------------
// Build-summary parsing. shadow-cljs prints one summary line per build:
//   [:examples/login-helix] Build completed. (196 files, 1 compiled, 1 warnings, 7.47s)
// `compile` exits 0 regardless of the warnings count, so we parse the count
// ourselves and treat any non-zero warnings as a failure. A build that
// errors hard instead prints `Build failed` (and shadow exits non-zero),
// which we surface separately via the spawn status.
// ---------------------------------------------------------------------------

const COMPLETED_RE =
  /\[(:[\w.-]+\/[\w.-]+)\]\s+Build completed\..*?(\d+)\s+warnings/g;
const FAILED_RE = /\[(:[\w.-]+\/[\w.-]+)\]\s+Build failed/g;

/**
 * Parse shadow-cljs compile output into per-build outcomes.
 *
 * @param {string} output  combined stdout+stderr of `shadow-cljs compile`.
 * @returns {{completed: Array<{build:string,warnings:number}>, failed: string[]}}
 */
function parseBuildSummaries(output) {
  const completed = [];
  const failed = [];
  let m;
  COMPLETED_RE.lastIndex = 0;
  while ((m = COMPLETED_RE.exec(output)) !== null) {
    completed.push({ build: m[1], warnings: Number(m[2]) });
  }
  FAILED_RE.lastIndex = 0;
  while ((m = FAILED_RE.exec(output)) !== null) {
    failed.push(m[1]);
  }
  return { completed, failed };
}

/**
 * The builds with a non-zero warning count, e.g.
 * [{ build: ':examples/login-helix', warnings: 1 }].
 */
function buildsWithWarnings(output) {
  return parseBuildSummaries(output).completed.filter((b) => b.warnings > 0);
}

module.exports = {
  readShadowEdn,
  stripEdnComments,
  enumerateExampleBuilds,
  parseBuildSummaries,
  buildsWithWarnings,
};

// ---------------------------------------------------------------------------
// CLI entry-point (skipped when require()'d by the test suite).
// ---------------------------------------------------------------------------
if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');

  const edn = readShadowEdn();
  const builds = enumerateExampleBuilds(edn);

  // Non-vacuous guard: a parser that silently recovers nothing must NOT let
  // the gate pass green having compiled zero builds. We know the project
  // ships well over a dozen example builds; require a sane floor.
  if (builds.length < 10) {
    console.error(
      `check-examples-compile: only ${builds.length} :examples/* build(s) ` +
        `recovered from shadow-cljs.edn — expected the full example set. ` +
        `Parser or shadow-cljs.edn drift; refusing to pass a vacuous gate.`,
    );
    process.exit(1);
  }

  if (listOnly) {
    for (const b of builds) console.log(b);
    process.exit(0);
  }

  // Resolve shadow-cljs's own JS entry-point and run it under THIS node
  // binary (shell-free, .cmd-free) — see SPAWN FORM in the header.
  let shadowRunner;
  try {
    shadowRunner = require.resolve('shadow-cljs/cli/runner.js', {
      paths: [IMPL_ROOT],
    });
  } catch {
    console.error(
      'check-examples-compile: could not resolve shadow-cljs. Run ' +
        `\`npm install\` in ${IMPL_ROOT} first.`,
    );
    process.exit(1);
  }

  const args = [shadowRunner, 'compile', ...builds];
  console.log(
    `check-examples-compile: compiling ${builds.length} declared ` +
      `:examples/* build(s):`,
  );
  for (const b of builds) console.log(`  ${b}`);
  console.log(`> shadow-cljs compile ${builds.join(' ')}`);

  // Tee shadow's output to the console live (so CI logs show progress and
  // any warning/error context) while accumulating it for warning analysis.
  // `compile` exits 0 even on warnings, so the captured text — not the exit
  // code alone — is the source of truth for the zero-warning bar.
  const child = spawn(process.execPath, args, {
    cwd: IMPL_ROOT,
    stdio: ['inherit', 'pipe', 'pipe'],
  });

  let captured = '';
  child.stdout.on('data', (chunk) => {
    captured += chunk;
    process.stdout.write(chunk);
  });
  child.stderr.on('data', (chunk) => {
    captured += chunk;
    process.stderr.write(chunk);
  });

  child.on('error', (err) => {
    console.error(`check-examples-compile: spawn failed — ${err.message}`);
    process.exit(1);
  });

  child.on('close', (code) => {
    // 1) Hard failure: a missing :require'd namespace / unbalanced form
    //    makes shadow exit non-zero (and print `Build failed`).
    if (code !== 0) {
      console.error(
        `\ncheck-examples-compile: shadow-cljs compile failed (exit ${code}). ` +
          `One or more example builds has a hard compile error (missing ns / ` +
          `:require / unbalanced form).`,
      );
      const { failed } = parseBuildSummaries(captured);
      if (failed.length > 0) {
        console.error(`  Failed build(s): ${failed.join(', ')}`);
      }
      process.exit(code == null ? 1 : code);
    }

    // 2) Soft failure: `compile` exits 0 on warnings (e.g. an :undeclared-var
    //    from a typo'd init-fn / symbol). Fail the gate so such a regression
    //    cannot ship green. All example builds are warning-free today, so any
    //    non-zero count is a real regression.
    const warned = buildsWithWarnings(captured);
    if (warned.length > 0) {
      console.error(
        `\ncheck-examples-compile: ${warned.length} example build(s) compiled ` +
          `with WARNINGS — failing the gate (warnings are treated as errors ` +
          `here because shadow-cljs 'compile' exits 0 on warnings and ` +
          `:warnings-as-errors only bites on 'release'):`,
      );
      for (const { build, warnings } of warned) {
        console.error(`  ${build}: ${warnings} warning(s)`);
      }
      console.error(
        `  See the per-build warning output above (e.g. an :undeclared-var ` +
          `from a typo'd init-fn / symbol is exactly the regression class ` +
          `this gate exists to catch).`,
      );
      process.exit(1);
    }

    console.log(
      `\ncheck-examples-compile: all ${builds.length} example builds compiled ` +
        `with zero warnings.`,
    );
  });
}
