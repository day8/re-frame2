#!/usr/bin/env node
/*
 * `test:examples-compile` — compile-coverage gate over EVERY declared
 * standalone `:examples/*` AND `:testbeds/*` shadow-cljs build.
 *
 * What this covers
 * ----------------
 * The `:examples/*` builds are declared shadow-cljs browser targets with
 * init-fns, and apart from the counter builds `test:bundle-isolation`
 * release-builds, no other automated gate compiles them. Without this gate a
 * namespace / init-fn / dependency / schema / machine / substrate-form
 * regression in any of them would ship GREEN until a human ran the example.
 * This is the compile layer: it adds no per-example `*.spec.cjs` under
 * `examples/` (examples are test-free) — it just COMPILES every declared
 * standalone example so a compile-time defect fails CI.
 *
 * THE SAME HOLE, ONE TREE OVER. `:testbeds/*` has it too, and worse,
 * because nothing else reaches those builds either. The classifier's
 * generic `testbeds/*` case arms `cljs_browser`, but the top-level
 * `testbeds/` tree holds no test files (only `.cljs` sources and one
 * colocated `spec.cjs` under `tenant_switcher/`), and no test in the armed
 * lane `:require`s a testbed namespace — the Xray e2e suites that read like
 * they do use their OWN `host-fixtures` copies. So that lane compiles none of
 * them, and without this gate a compile break confined to a top-level testbed
 * would land green at PR time, caught only by the Xray FULL feature gate
 * (nightly). (`tenant-switcher` also has the `tenant_switcher_smoke` gate;
 * `:testbeds/panel-gallery` lives under `tools/xray/testbeds/`.) Sweeping
 * the prefix here is the cheapest lane: a pure `shadow-cljs compile` in a job
 * that already exists, sharing that job's compilation cache, buying no
 * browser execution nobody asked for.
 *
 * THE FILE AND SCRIPT NAMES READ "examples", deliberately. Renaming
 * them is a required-check rename plus an npm-script rename plus a
 * classifier-roster edit, for no coverage gained. What the
 * gate SWEEPS is `COMPILED_BUILD_PREFIXES` below; that is the honest roster.
 *
 * Auto-covering by construction
 * -----------------------------
 * The build list is DERIVED from `shadow-cljs.edn` (the `:examples/*` and
 * `:testbeds/*` build ids) rather than hardcoded, so a NEWLY-declared build
 * under either prefix is swept by this gate the moment it lands — no second
 * edit, no drift. This mirrors the `:dev-http` drift-guard approach in
 * `dev-testbed.test.cjs`: shadow-cljs.edn is the single source
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
 * note on explicit build ids applies to `watch`, not here).
 *
 * WARNINGS ARE FAILURES (teeth)
 * -----------------------------
 * Every swept build sets `:warnings-as-errors true` in its own
 * `:compiler-options`, so a warning — an `:undeclared-var` from a typo'd
 * init-fn / symbol, a redef, an externs-inference miss — fails that build
 * and shadow-cljs exits non-zero, under `compile` as under `release`, cold
 * or warm (shadow-cljs never caches a file that warned). The policy lives
 * in shadow-cljs.edn rather than here, so a bare `npx shadow-cljs compile
 * examples/<name>` is exactly as strict as this gate. What this gate adds
 * is the guarantee that the flag is there: it refuses a swept build whose
 * block does not set it (`buildsMissingWarningsAsErrors`), so a newly
 * declared build cannot compile its warnings green. Every swept build
 * compiles with zero warnings, so the flag is a clean bar, not a noisy one.
 *
 * SPAWN FORM: resolve shadow-cljs's own JS
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
 * `check-examples-compile.test.cjs`, which pins them (non-vacuous under
 * EACH swept prefix, every swept build warnings-fatal, every requested build
 * accounted for) so this gate keeps its teeth.
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
// the dev-testbed drift guard uses.
// ---------------------------------------------------------------------------

/**
 * The build-id prefixes this gate sweeps, each with the floor its
 * enumeration must clear.
 *
 * THE FLOOR IS PER PREFIX, not a single total, and that is the point.
 * A total floor is satisfied by the examples alone: drop the
 * `testbeds` alternative out of the pattern below and the example builds
 * still clear any total worth setting, so the gate would go on passing
 * green having quietly stopped compiling every testbed build — the precise
 * vacuous-pass shape a floor exists to refuse. A floor per prefix cannot be
 * satisfied by a sibling.
 *
 * The numbers are deliberately well under the live counts (`--list` prints
 * them) — they are a non-vacuity bar, not a census. A census here would
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
 * (e.g. the `:examples/xray-rhs-smoke` named in a comment) can't be mistaken
 * for a live build. Naive but sufficient: the build-id keys we scan never
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

/**
 * The swept builds whose own block does not set `:warnings-as-errors true`,
 * in sorted order. Empty means every swept build is warnings-fatal under any
 * invocation. A block runs from its build id to the next two-space-indented
 * key, which is how every build def in shadow-cljs.edn is laid out.
 */
function buildsMissingWarningsAsErrors(edn) {
  const src = stripEdnComments(edn);
  const swept = new Set(enumerateCompiledBuilds(edn));
  const keys = [...src.matchAll(/^ {2}:(\S+)/gm)];
  return keys
    .map((m, i) => ({
      build: m[1],
      block: src.slice(m.index, i + 1 < keys.length ? keys[i + 1].index : src.length),
    }))
    .filter(({ build, block }) => swept.has(build) && !/:warnings-as-errors\s+true\b/.test(block))
    .map(({ build }) => build)
    .sort();
}

// ---------------------------------------------------------------------------
// Build-summary parsing. shadow-cljs prints one summary line per build:
//   [:examples/login-helix] Build completed. (196 files, 1 compiled, 0 warnings, 7.47s)
// A warning fails the build (`:warnings-as-errors`, above), so the summary
// carries no verdict here; it is read only to confirm that every requested
// build was compiled.
//
// Two other lanes read the same line for its warning count, each with its own
// parser: `bench/fresco/src/re_frame/bench/fresco/lane_build.cjs`
// (`:fresco-bench`) and `fresco/scripts/check_modules_compile.cjs`
// (`:fresco-modules-compile`). They are deliberately not unified with this one,
// which reads no count at all.
//
// THE SLASH IN THE PATTERN BELOW IS LOAD-BEARING, and not merely an id capture:
// `reconcileRequestedBuilds` treats a summary whose id was NOT requested as a
// failure, so widening this to bare-keyword ids would admit every id shadow
// prints in the invocation and turn that rule into a live question. Anyone
// sharing this regex owes that measurement first.
// ---------------------------------------------------------------------------

const COMPLETED_RE = /\[(:[\w.-]+\/[\w.-]+)\]\s+Build completed\./g;
const FAILED_RE = /\[(:[\w.-]+\/[\w.-]+)\]\s+Build failed/g;

/**
 * Parse shadow-cljs compile output into the build ids it reports as
 * completed and as failed, in output order.
 *
 * @param {string} output  combined stdout+stderr of `shadow-cljs compile`.
 * @returns {{completed: string[], failed: string[]}}
 */
function parseBuildSummaries(output) {
  const ids = (re) => [...output.matchAll(re)].map((m) => m[1]);
  return { completed: ids(COMPLETED_RE), failed: ids(FAILED_RE) };
}

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
 * assumption the child exited 0. Returns a list of problem strings; an empty
 * list means every requested build produced exactly one parsable completed
 * summary.
 *
 * THE FALSE-GREEN THIS CLOSES. A zero exit says no build failed, not that
 * every requested build was compiled. If a requested build's summary line
 * never appears in the captured output — or appears in a shape the summary
 * regex does not match (a shadow-cljs format change, a truncated line) — the
 * gate would report SUCCESS having verified nothing about that build. So a
 * missing or unparseable summary is a FAILURE, not a silent pass:
 *
 *   - every requested build must have EXACTLY ONE completed summary;
 *   - a build with zero summaries is `missing` (drift / disappeared);
 *   - a build with more than one summary is `duplicate` (ambiguous output);
 *   - a completed summary for a build that was NOT requested is `unexpected`.
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
  for (const build of completed) {
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
          `no longer matches the parser — nothing confirms it compiled; ` +
          `refusing to pass it green).`,
      );
    } else if (n > 1) {
      problems.push(
        `${id}: ${n} "Build completed." summaries parsed (expected exactly ` +
          `one) — ambiguous output; cannot tell which compile it reports.`,
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

  return problems;
}

module.exports = {
  COMPILED_BUILD_PREFIXES,
  readShadowEdn,
  stripEdnComments,
  enumerateCompiledBuilds,
  prefixesBelowFloor,
  buildsMissingWarningsAsErrors,
  parseBuildSummaries,
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

  const unflagged = buildsMissingWarningsAsErrors(edn);
  if (unflagged.length > 0) {
    console.error(
      `check-examples-compile: ${unflagged.length} swept build(s) do not set ` +
        `:warnings-as-errors true, so a warning would compile green:`,
    );
    for (const b of unflagged) console.error(`  ${b}`);
    console.error(
      '  Add `:compiler-options {:warnings-as-errors true}` to each build in ' +
        'shadow-cljs.edn.',
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
      `${Object.keys(COMPILED_BUILD_PREFIXES)
        .map((p) => `:${p}/*`)
        .join(' + ')} build(s):`,
  );
  for (const b of builds) console.log(`  ${b}`);
  console.log(`> shadow-cljs compile ${builds.join(' ')}`);

  // Tee shadow's output to the console live (so CI logs show progress and
  // any warning/error context) while accumulating it for the coverage
  // reconciliation below.
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
    // 1) A missing :require'd namespace, an unbalanced form or a warning
    //    (fatal under :warnings-as-errors) makes shadow exit non-zero. It
    //    compiles the requested builds in turn and stops at the first
    //    failure, so the builds after it were not compiled at all.
    if (code !== 0) {
      console.error(
        `\ncheck-examples-compile: shadow-cljs compile failed (exit ${code}). ` +
          `A swept build has a compile error or a warning (missing ns / ` +
          `:require / unbalanced form / :undeclared-var ...); its message is ` +
          `printed above, and the builds after it were not compiled.`,
      );
      const { failed } = parseBuildSummaries(captured);
      if (failed.length > 0) {
        console.error(`  Failed build(s): ${failed.join(', ')}`);
      }
      process.exit(code == null ? 1 : code);
    }

    // 2) Coverage reconciliation: a clean exit is NOT sufficient. A
    //    requested build whose summary is missing/unparsable, or a
    //    duplicate/unexpected summary, means nothing confirms that build
    //    compiled — a false green. Verify every requested build produced
    //    exactly one parsable completed summary before declaring success.
    const coverageProblems = reconcileRequestedBuilds(builds, captured);
    if (coverageProblems.length > 0) {
      console.error(
        `\ncheck-examples-compile: ${coverageProblems.length} build-summary ` +
          `coverage problem(s) — shadow-cljs exited 0 but the gate could not ` +
          `confirm that every requested build compiled. A missing/unparsable ` +
          `summary FAILS the gate:`,
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
