#!/usr/bin/env node
/*
 * `test:examples-compile` — compile-coverage gate over EVERY declared
 * standalone `:examples/*` AND `:testbeds/*` shadow-cljs build
 * (rf2-0vav5.1 + rf2-cn6kc.1 + rf2-in6c4).
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
 * THE SAME GAP, ONE TREE OVER (rf2-in6c4). `:testbeds/*` had it too, and
 * worse, because nothing else reached those builds either. The classifier's
 * generic `testbeds/*` case arms `cljs_browser`, but the top-level
 * `testbeds/` tree holds ZERO test files (measured: 13 `.cljs`, 0 `_test`,
 * 0 `.clj`, and one colocated `spec.cjs` under `tenant_switcher/`), and no
 * test in the armed lane `:require`s a testbed namespace — the Xray e2e
 * suites that read like they do use their OWN `host-fixtures` copies. So the
 * lane compiled none of them, and a compile break confined to a top-level
 * testbed landed green at PR time, caught only by the Xray FULL feature gate
 * (nightly). Twelve non-tenant top-level builds sat behind that hole
 * (`tenant-switcher` is the thirteenth and already has the
 * `tenant_switcher_smoke` gate; `:testbeds/panel-gallery` lives under
 * `tools/xray/testbeds/` and is the fourteenth). Adding the prefix here was the cheapest of the
 * three lanes weighed on the bead: a pure `shadow-cljs compile` in a job
 * that already exists, sharing that job's compilation cache, buying no
 * browser execution nobody asked for.
 *
 * THE FILE AND SCRIPT NAMES STILL READ "examples", deliberately. Renaming
 * them is a required-check rename plus an npm-script rename plus a
 * classifier-roster edit, for no coverage gained — the same trade
 * `ui-scaffold-smoke` records in test.yml for its own stale name. What the
 * gate SWEEPS is `COMPILED_BUILD_PREFIXES` below; that is the honest roster.
 *
 * Auto-covering by construction
 * -----------------------------
 * The build list is DERIVED from `shadow-cljs.edn` (the `:examples/*` and
 * `:testbeds/*` build ids) rather than hardcoded, so a NEWLY-declared build
 * under either prefix is swept by this gate the moment it lands — no second
 * edit, no drift. This mirrors the `:dev-http` drift-guard approach in
 * `dev-testbed.test.cjs` (rf2-d3fb7.1): shadow-cljs.edn is the single source
 * of truth and is read here ONLY (it is a hot-zone file — never edited by
 * this script).
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
 *   node scripts/check-examples-compile.cjs           # compile every swept build
 *   node scripts/check-examples-compile.cjs --list     # print the derived build list, exit 0
 *
 * The pure enumeration + parser are exported for
 * `check-examples-compile.test.cjs`, which pins the parser (non-vacuous
 * under EACH swept prefix, covers the previously-uncovered builds) so this
 * gate keeps its teeth.
 */

'use strict';

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const { IMPL_ROOT } = require('./_path-policy.cjs');

// ---------------------------------------------------------------------------
// shadow-cljs.edn enumeration. We hand-roll a focused scan rather than pull
// in an EDN dependency — the only shape we read is top-level build-id keys
// (`:examples/<name> {`, `:testbeds/<name> {`). This matches the parser style
// already used by the dev-testbed drift guard (rf2-d3fb7.1).
// ---------------------------------------------------------------------------

/**
 * The build-id prefixes this gate sweeps, each with the floor its
 * enumeration must clear.
 *
 * THE FLOOR IS PER PREFIX, not a single total, and that is the point
 * (rf2-in6c4). A total floor is satisfied by the examples alone: drop the
 * `testbeds` alternative out of the pattern below and 39 example builds
 * still clear any total worth setting, so the gate would go on passing
 * green having quietly stopped compiling fifteen builds — the precise
 * vacuous-pass shape the original floor was added to refuse, reintroduced
 * by widening the roster. A floor per prefix cannot be satisfied by a
 * sibling.
 *
 * The numbers are deliberately well under the live counts (39 examples, 15
 * testbeds) — they are a non-vacuity bar, not a census. A census here would
 * red on every legitimate addition and removal.
 */
const COMPILED_BUILD_PREFIXES = Object.freeze({
  examples: 10,
  testbeds: 10,
});

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
 * Enumerate every declared standalone build id under a swept prefix, in
 * sorted order. A build def is a top-level `:<prefix>/<name> {` key. Returns
 * the colon-stripped coords shadow-cljs's CLI expects (e.g.
 * `examples/login-uix`, `testbeds/deep-machine`), de-duplicated and sorted
 * for a stable gate list.
 *
 * The keys are matched at line-start indentation (two spaces) followed by
 * `:<prefix>/<name>` and an opening brace, mirroring how build defs are
 * written throughout shadow-cljs.edn — so a `:examples/...` token that
 * appears mid-line (in prose or a nested map) is never picked up as a build.
 *
 * `:story-static/*` is NOT swept: it is a `release`-shaped export driven by
 * its own `story:build` script and gated by `story_static_gate`, so
 * compiling it here would duplicate a gate rather than close a hole.
 */
function enumerateCompiledBuilds(edn) {
  const src = stripEdnComments(edn);
  const prefixes = Object.keys(COMPILED_BUILD_PREFIXES).join('|');
  const re = new RegExp(`^\\s*:((?:${prefixes})\\/[\\w.-]+)\\s*\\{`, 'gm');
  const found = new Set();
  let m;
  while ((m = re.exec(src)) !== null) {
    found.add(m[1]);
  }
  return [...found].sort();
}

/**
 * The swept prefixes whose enumerated count falls below their declared
 * floor, as `[{prefix, count, floor}]`. Empty means every prefix is
 * non-vacuously represented. See COMPILED_BUILD_PREFIXES for why this is
 * checked per prefix rather than in total.
 */
function prefixesBelowFloor(builds) {
  return Object.entries(COMPILED_BUILD_PREFIXES)
    .map(([prefix, floor]) => ({
      prefix,
      floor,
      count: builds.filter((b) => b.startsWith(`${prefix}/`)).length,
    }))
    .filter(({ count, floor }) => count < floor);
}

// ---------------------------------------------------------------------------
// Build-summary parsing. shadow-cljs prints one summary line per build:
//   [:examples/login-helix] Build completed. (196 files, 1 compiled, 1 warnings, 7.47s)
// `compile` exits 0 regardless of the warnings count, so we parse the count
// ourselves and treat any non-zero warnings as a failure. A build that
// errors hard instead prints `Build failed` (and shadow exits non-zero),
// which we surface separately via the spawn status.
//
// TWO OTHER LANES READ THIS SAME LINE, each with its own four-line parser:
// `scripts/compile-node-test.cjs` (the `:node-test` family) and
// `bench/hicasso/src/re_frame/bench/hicasso/lane_build.cjs` (`:hicasso-bench`). All
// three refuse an unreadable summary; they are deliberately NOT unified, and
// `compile-node-test.cjs`'s header carries the measured reasons and the test
// for whether a fourth lane should mint its own (rf2-040s1). Read it before
// generalising anything here.
//
// THE SLASH IN THE PATTERN BELOW IS LOAD-BEARING, and not merely an id capture:
// `reconcileRequestedBuilds` treats a summary whose id was NOT requested as a
// failure, so widening this to bare-keyword ids would admit every id shadow
// prints in the invocation and turn that rule into a live question. Anyone
// sharing this regex owes that measurement first.
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

/**
 * A warning-shaped marker that shadow-cljs prints when a build emits a
 * compile warning — `------ WARNING #1 - :undeclared-var --------------`.
 * Used as a corroborating signal: if such a marker appears in the captured
 * output but NO parsable per-build summary carries `warnings > 0`, the
 * summary parser has drifted (or shadow's summary format changed) and the
 * warning evidence would otherwise be silently erased — a false green.
 */
const WARNING_MARKER_RE = /-{2,}\s*WARNING\b/;

/**
 * Normalise a build coord to the colon-prefixed form shadow-cljs prints in
 * its summary lines (`:examples/login-uix`). The enumeration returns the
 * colon-STRIPPED form the CLI expects (`examples/login-uix`); the summary
 * parser captures the colon-PREFIXED form. Reconciling the two requires one
 * canonical shape — we pick the `:`-prefixed form (the summary form).
 */
function normaliseBuildId(id) {
  return id.startsWith(':') ? id : `:${id}`;
}

/**
 * Reconcile the parsed compile summaries against the list of builds that
 * were REQUESTED of `shadow-cljs compile` (the enumeration), on the
 * assumption the child exited 0 (no hard `Build failed`). Returns a list of
 * problem strings; an empty list means every requested build produced
 * exactly one parsable completed summary AND no orphan warning marker was
 * left unaccounted for.
 *
 * THE FALSE-GREEN THIS CLOSES (rf2-nlnd9y.1). The gate's teeth are the
 * per-build `warnings` count parsed out of each summary line. But the gate
 * previously failed ONLY on a parsed `warnings > 0` row (or a non-zero child
 * exit). If a requested build's summary line never appeared in the captured
 * output — or appeared in a shape the summary regex no longer matches (a
 * shadow-cljs format change, a `1 warning` singular, a truncated line) —
 * then `buildsWithWarnings` returns `[]`, the child still exited 0, and the
 * gate reported SUCCESS having verified nothing about that build. A missing
 * or unparseable summary is now a FAILURE, not a silent pass:
 *
 *   - every requested build must have EXACTLY ONE completed summary;
 *   - a build with zero summaries is `missing` (drift / disappeared);
 *   - a build with more than one summary is `duplicate` (ambiguous output);
 *   - a completed summary for a build that was NOT requested is `unexpected`;
 *   - a WARNING marker in the output with no parsable warning row anywhere
 *     is `orphan-warning` (the parser drifted past warning evidence).
 *
 * @param {string[]} requested  enumerated build ids (colon-stripped form).
 * @param {string}   output     combined compile output.
 * @returns {string[]} human-readable problem descriptions (empty = OK).
 */
function reconcileRequestedBuilds(requested, output) {
  const { completed } = parseBuildSummaries(output);
  const problems = [];

  // Count completed summaries per (normalised) build coord.
  const counts = new Map();
  for (const { build } of completed) {
    const k = normaliseBuildId(build);
    counts.set(k, (counts.get(k) || 0) + 1);
  }

  const requestedSet = new Set(requested.map(normaliseBuildId));

  // 1) Every requested build must have exactly one completed summary.
  for (const id of requestedSet) {
    const n = counts.get(id) || 0;
    if (n === 0) {
      problems.push(
        `${id}: requested but NO parsable "Build completed." summary was ` +
          `found in shadow-cljs output (the build's summary disappeared or ` +
          `no longer matches the parser — warning evidence for it would be ` +
          `silently erased; refusing to pass it green).`,
      );
    } else if (n > 1) {
      problems.push(
        `${id}: ${n} "Build completed." summaries parsed (expected exactly ` +
          `one) — ambiguous output; cannot trust the warning count.`,
      );
    }
  }

  // 2) A completed summary for a build that was never requested is drift in
  //    the OTHER direction (the enumeration and the compiled set disagree).
  for (const id of counts.keys()) {
    if (!requestedSet.has(id)) {
      problems.push(
        `${id}: a "Build completed." summary was parsed for a build that ` +
          `was NOT in the requested set — enumeration/output mismatch.`,
      );
    }
  }

  // 3) A WARNING marker with no parsable warning row anywhere means the
  //    summary parser drifted past real warning evidence (the exact
  //    false-green class: warnings present, exit 0, parser blind).
  if (
    WARNING_MARKER_RE.test(output) &&
    buildsWithWarnings(output).length === 0
  ) {
    problems.push(
      `a WARNING marker appears in the compile output but NO per-build ` +
        `summary carries warnings>0 — the summary parser has drifted past ` +
        `real warning evidence (warnings would ship green).`,
    );
  }

  return problems;
}

module.exports = {
  COMPILED_BUILD_PREFIXES,
  readShadowEdn,
  stripEdnComments,
  enumerateCompiledBuilds,
  prefixesBelowFloor,
  parseBuildSummaries,
  buildsWithWarnings,
  normaliseBuildId,
  reconcileRequestedBuilds,
};

// ---------------------------------------------------------------------------
// CLI entry-point (skipped when require()'d by the test suite).
// ---------------------------------------------------------------------------
if (require.main === module) {
  const listOnly = process.argv.slice(2).includes('--list');

  const edn = readShadowEdn();
  const builds = enumerateCompiledBuilds(edn);

  // Non-vacuous guard: a parser that silently recovers nothing must NOT let
  // the gate pass green having compiled zero builds. Checked PER PREFIX, so
  // a healthy roster under one prefix cannot cover for an empty one.
  const starved = prefixesBelowFloor(builds);
  if (starved.length > 0) {
    for (const { prefix, count, floor } of starved) {
      console.error(
        `check-examples-compile: only ${count} :${prefix}/* build(s) ` +
          `recovered from shadow-cljs.edn (floor ${floor}) — expected the ` +
          `full ${prefix} set. Parser or shadow-cljs.edn drift; refusing to ` +
          `pass a vacuous gate.`,
      );
    }
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
      `${Object.keys(COMPILED_BUILD_PREFIXES)
        .map((p) => `:${p}/*`)
        .join(' + ')} build(s):`,
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
          `One or more swept builds has a hard compile error (missing ns / ` +
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
        `\ncheck-examples-compile: ${warned.length} build(s) compiled ` +
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

    // 3) Coverage reconciliation (rf2-nlnd9y.1): a clean exit + zero parsed
    //    warning rows is NOT sufficient. A requested build whose summary is
    //    missing/unparsable, a duplicate/unexpected summary, or a WARNING
    //    marker the parser missed all mean the warning analysis above was
    //    BLIND for at least one build — a false green. Verify every requested
    //    build produced exactly one parsable completed summary before
    //    declaring success.
    const coverageProblems = reconcileRequestedBuilds(builds, captured);
    if (coverageProblems.length > 0) {
      console.error(
        `\ncheck-examples-compile: ${coverageProblems.length} build-summary ` +
          `coverage problem(s) — shadow-cljs exited 0 but the gate could not ` +
          `confirm a clean warning analysis for every requested build. A ` +
          `missing/unparsable summary FAILS the gate (it would otherwise ship ` +
          `a warning silently green):`,
      );
      for (const p of coverageProblems) console.error(`  ${p}`);
      process.exit(1);
    }

    console.log(
      `\ncheck-examples-compile: all ${builds.length} swept builds compiled ` +
        `with zero warnings (every requested build produced exactly one ` +
        `parsable completed summary).`,
    );
  });
}
