#!/usr/bin/env node
/*
 * Per-namespace test-isolation gate (rf2-32siq3.44).
 *
 * THE GAP THIS CLOSES
 * -------------------
 * `npm run test:cljs` compiles ALL `*_cljs_test` namespaces into one
 * consolidated node-test bundle and runs them in a single process. That
 * shares one runtime: the installed substrate adapter, the registrar, the
 * late-bind directory, machine/trace counters — all process-global state.
 *
 * EP-0023 collapse made `make-frame` allocate a RUNNABLE
 * backing record (app-db / queue / sub-cache), which needs an installed
 * substrate adapter. A test namespace whose fixture FORGETS to install its
 * own adapter (via `make-reset-runtime-fixture {:adapter ...}` or
 * `rf/init!`) is still GREEN in the consolidated bundle whenever an
 * EARLIER namespace happened to leave an adapter installed — the suite
 * ORDERING masks a self-incomplete fixture. Run that same namespace alone
 * and it errors with `:rf.error/no-adapter-installed`.
 *
 * This is the shared-node-test-bundle fragility class: a fixture that
 * relies on bundle pollution passes the gate. The concrete instance
 * (`live-frame-reload-cljs-test`, 11 errors standalone yet green in CI)
 * was fixed by the sibling bead; this gate stops the CLASS from recurring.
 *
 * THE CHECK
 * ---------
 * Run each curated runtime-construction test namespace ALONE in a fresh
 * `out/node-test.js` process (the runner's focused `--test=<ns>` selector,
 * which already rejects a no-match selector — rf2-lbo79.1). A namespace
 * that relies on a sibling's leaked adapter goes RED standalone, so the
 * gate fails at the SOURCE: the self-incomplete fixture, not a victim
 * downstream.
 *
 * WHAT A STANDALONE RED MEANS — AND WHAT IT DOES NOT (rf2-v8561)
 * --------------------------------------------------------------
 * A non-zero exit from one standalone run has THREE possible causes, and
 * for most of this gate's life it reported all three as the first one:
 *
 *   1. ISOLATION — the namespace ran its tests and they failed. This is
 *      the defect the gate exists to find, and the fixture remedy applies.
 *   2. ROSTER — the selector matched nothing, because the namespace below
 *      was renamed or deleted and this list was not updated. The runner
 *      says so ("no tests matched --test= selector(s)") and exits 1. Twice
 *      already the gate answered that with the fixture remedy and a human
 *      had to work out what it really meant (`9dd9633d0b`, `d3969eb67b`).
 *   3. ENVIRONMENT — `out/node-test.js` moved underneath the sweep. The
 *      sweep spawns one node process per namespace over tens of seconds
 *      against a bundle a CONCURRENT compile may delete and rewrite at any
 *      moment; `compile-node-test.cjs` unlinks `:output-to` before every
 *      compile precisely so a failed one leaves nothing stale (rf2-6t03c),
 *      which means a compile starting mid-sweep pulls the bundle out from
 *      under every run still to come. Nothing about a fixture is under
 *      test at that point.
 *
 * The three are told apart by ONE structural fact: `--test=<ns>` is a
 * RUNTIME selector over a bundle that links EVERY test namespace, so every
 * run loads the whole graph no matter which namespace it selects. A crash
 * at module load therefore cannot single out a namespace — it hits all of
 * them equally. So a red carrying cljs.test's `Ran N tests containing M
 * assertions.` summary got far enough to be a verdict about the namespace,
 * and a red WITHOUT one never did. Cause 3's signature is a red suffix:
 * the sweep is green until the compile lands, and everything after it
 * fails identically. That is what was reported here — the last three
 * roster entries, consecutively, in a worktree building other things.
 *
 * A sweep whose bundle moved is INDETERMINATE, not red: its green runs are
 * worth no more than its red ones, so the gate reports exit 2 and asks for
 * a rerun rather than naming namespaces it cannot vouch for. Refusing to
 * convert an unobserved cause into a confident accusation is the same
 * discipline the roster's own entries were written under.
 *
 * WHAT THIS GATE IS NOT
 * ---------------------
 * This gate detects ONE direction only: deterministic "red alone, green in
 * company" fixture defects. It does NOT certify test-order independence. It
 * is structurally blind to the inverse direction — green alone, red in
 * company (e.g. a wall-clock captured in a top-level `def` decaying across
 * the suite prefix, rf2-ybqse): running such a namespace alone makes the bug
 * LESS likely, not more. And it is only probabilistically sensitive to
 * intermittent races (rf2-i36h6 was red alone on just 4 of 10 runs
 * unfixed). Do NOT add run-counting, order permutation, or timing analysis
 * here.
 *
 * The bar a FIX in the order-dependence family must clear is the rf2-ybqse
 * structural-unreachability standard, enforced at REVIEW time: prove the
 * failure UNREACHABLE (a measured margin, an invariant, an awaited
 * thenable) rather than merely unobserved, with a positive control that
 * fails in the consolidated run.
 *
 * WHY A CURATED ALLOWLIST, NOT A BLANKET SCAN
 * -------------------------------------------
 * Running every `*_cljs_test` ns alone would be slow (one node process
 * each) and a naive "does this file install an adapter?" STATIC lint is
 * unworkably noisy: `rf/init!` is BOTH a constructor AND the adapter
 * install call, story/test helpers seat adapters through their own
 * fixtures, and `make-frame` appears as an error-id keyword in many files
 * that never construct a frame. So, like `check_ambient_durable_reads.py`'s
 * durable-write suffix allowlist, this gate scopes to an EXPLICIT list of
 * the EP-0023 image/frame runtime-construction namespaces — the lineage
 * whose fixtures MUST self-install an adapter. Adding such a namespace is a
 * conscious one-line edit here (and the gate then guards it).
 *
 * Exit 0 on PASS (every listed ns green standalone, each having run a
 * non-zero number of tests), 1 on a real isolation FAIL, 2 on a setup
 * error — which now includes a stale roster and an indeterminate sweep.
 */

'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------------------
// The curated scan surface. Each namespace is run ALONE; a fixture leaning on
// a sibling's leaked runtime state goes red standalone. The roster carries TWO
// strata:
//
//   1. the EP-0023 image/frame runtime-construction lineage, whose fixtures
//      MUST self-install a substrate adapter (a runnable `make-frame` backing
//      record needs one);
//   2. shipped order-dependence regressions whose failure signature is
//      red-alone — namespaces that actually broke this way once, kept here so
//      the same fixture cannot silently re-acquire the dependency.
//
// To add a namespace: append its ns symbol (the `(ns ...)` name) to the
// matching stratum. Run `node scripts/check-per-ns-isolation.cjs --list` to
// see the live set. Only add a namespace whose regression is visible ALONE —
// see WHAT THIS GATE IS NOT above.
// ---------------------------------------------------------------------------
const ISOLATION_NAMESPACES = [
  // --- 1. EP-0023 frame-construction lineage ---
  're-frame.conformance-corpus-cljs-test',
  're-frame.ep0023-conformance-cljs-test',
  're-frame.example-frame-scoping-cljs-test',
  're-frame.frame-classification-cljs-test',
  're-frame.frame-resolution-cljs-test',
  're-frame.frame-root-lifecycle-cljs-test',
  're-frame.frame-teardown-report-cljs-test',
  're-frame.live-frame-cljs-test',
  're-frame.live-frame-reload-cljs-test',
  're-frame.live-run-frame-resolution-cljs-test',
  're-frame.multi-frame-isolation-cljs-test',
  're-frame.projection-cljs-test',
  're-frame.router-carried-frame-cljs-test',
  're-frame.subs-override-seam-cljs-test',
  're-frame.views-current-component-cljs-test',

  // --- 2. Shipped order-dependence regressions (red-alone signature) ---
  // rf2-oslyz (#6367): passed only because an earlier namespace had called
  // `init!`; alone it gave 5 `:rf.error/no-adapter-installed` errors, and in
  // the other selection order 5 `:rf.error/image-duplicate-id`. Deleting its
  // adapter self-install is visible ONLY here — the gate's exact design class.
  're-frame.story.open-in-editor-ownership-cljs-test',
  // rf2-i36h6 (#6385): yielded one `setTimeout 0` on the premise that a
  // macrotask lands after React `act` settles. It does not. Deterministically
  // green solo post-fix, so zero noise while healthy; its regression is masked
  // in company by the warmed `act` path, making this gate the only automatic
  // detector.
  're-frame.story.play.presence-real-clock-cljs-test',
];

const NODE_TEST_BUNDLE = 'out/node-test.js';

function implementationDir() {
  // scripts/ lives directly under implementation/.
  return path.resolve(__dirname, '..');
}

function compileBundle(implDir) {
  process.stderr.write('compiling node-test bundle...\n');
  // Hardened, shell-free spawn (per the script-spawn-policy gate): resolve
  // shadow-cljs's JS entry-point and run it under THIS node binary, so the OS
  // never interprets a bare `npx` / `.cmd` shim (the rf2-33vvc command-hijack
  // class on Windows).
  let runner;
  try {
    runner = require.resolve('shadow-cljs/cli/runner.js');
  } catch (e) {
    process.stderr.write(
      'error: cannot resolve shadow-cljs (run `npm install` in implementation/).\n'
    );
    return false;
  }
  const r = spawnSync(process.execPath, [runner, 'compile', 'node-test'], {
    cwd: implDir,
    stdio: 'inherit',
  });
  if (r.status !== 0) {
    process.stderr.write('error: node-test compile failed.\n');
    return false;
  }
  return true;
}

function runNamespaceAlone(implDir, ns) {
  const r = spawnSync(process.execPath, [NODE_TEST_BUNDLE, `--test=${ns}`], {
    cwd: implDir,
    encoding: 'utf8',
  });
  return {
    status: r.status,
    // A spawn that never produced a process (ENOENT — the bundle was
    // unlinked between the sweep's existence check and this run) leaves
    // `status` null and reports through `error` instead.
    spawnError: r.error ? String(r.error.message || r.error) : null,
    stdout: r.stdout || '',
    stderr: r.stderr || '',
  };
}

// ---------------------------------------------------------------------------
// Classification (rf2-v8561). Pure, so `--self-test` can pin it without a
// compile: everything it needs is in the run's own output plus whether the
// bundle moved. See WHAT A STANDALONE RED MEANS in the header.
// ---------------------------------------------------------------------------

// cljs.test's canonical closing summary, printed by re-frame.test-quiet on
// every run that reaches the end — green or red. Its PRESENCE is the proof
// that the process got past module load and actually executed tests; its
// numbers are the proof that the selector matched something real.
const SUMMARY_RE = /^Ran (\d+) tests? containing (\d+) assertions?\./m;

// The runner's unmatched-selector refusal (shadow_node.cljs `execute-cli`):
// a `--test=` that matches no test var must never report a 0-test success,
// so it prints this and exits 1 before running anything.
const UNMATCHED_SELECTOR_RE = /^ERROR: no tests matched --test= selector/m;

function parseSummary(text) {
  const m = SUMMARY_RE.exec(text);
  if (!m) return null;
  return { tests: Number(m[1]), assertions: Number(m[2]) };
}

/**
 * Classify one standalone run. `bundleMoved` is true once `out/node-test.js`
 * has been seen to differ from the fingerprint taken before the sweep began.
 *
 * Returns { kind, summary, reason } where kind is one of:
 *   'green'       — ran tests, all passed
 *   'isolation'   — ran tests, they failed: the defect this gate hunts
 *   'roster'      — the selector matched nothing (this list is stale)
 *   'environment' — the run never became a verdict about the namespace
 */
function classifyRun(run, { bundleMoved } = {}) {
  const text = run.stdout + run.stderr;
  const summary = parseSummary(text);

  // The substrate moved: no run in this sweep — red OR green — is evidence
  // about a fixture, so do not let one masquerade as either.
  if (bundleMoved) {
    return { kind: 'environment', summary, reason: 'the bundle changed on disk during the sweep' };
  }
  if (run.spawnError) {
    return { kind: 'environment', summary, reason: `the bundle could not be spawned (${run.spawnError})` };
  }
  if (run.status === null) {
    return { kind: 'environment', summary, reason: 'the process was killed before it could report' };
  }
  if (UNMATCHED_SELECTOR_RE.test(text)) {
    return { kind: 'roster', summary, reason: 'the selector matched no test var — renamed or deleted namespace' };
  }
  if (summary === null) {
    // Module load links EVERY test namespace, so a crash before the summary
    // cannot be specific to the selected one — see the header.
    return { kind: 'environment', summary, reason: 'the run produced no cljs.test summary — it died before running tests' };
  }
  if (run.status === 0) {
    if (summary.tests === 0) {
      // Belt to the runner's own braces: a green that ran nothing certifies
      // nothing, and this gate must not report it as coverage.
      return { kind: 'environment', summary, reason: 'exited green having run 0 tests' };
    }
    return { kind: 'green', summary, reason: null };
  }
  return { kind: 'isolation', summary, reason: null };
}

/**
 * Size + mtime of the consolidated bundle, or null when it is absent. Cheap
 * enough to take after every run, which is what catches a compile landing
 * between two of them.
 */
function bundleFingerprint(bundlePath) {
  try {
    const st = fs.statSync(bundlePath);
    return { size: st.size, mtimeMs: st.mtimeMs };
  } catch (e) {
    return null;
  }
}

function sameBundle(a, b) {
  if (a === null || b === null) return a === b;
  return a.size === b.size && a.mtimeMs === b.mtimeMs;
}

function main(argv) {
  const args = new Set(argv);

  if (args.has('--help') || args.has('-h')) {
    process.stdout.write(
      'Per-namespace test-isolation gate (rf2-32siq3.44).\n\n' +
        'Usage:\n' +
        '  node scripts/check-per-ns-isolation.cjs [--compile] [--list] [--self-test]\n\n' +
        '  --compile    recompile out/node-test.js first (CI runs test:cljs ahead,\n' +
        '               so the bundle is normally already current)\n' +
        '  --list       print the curated namespace set and exit\n' +
        '  --self-test  prove the gate detects a no-adapter standalone failure\n' +
        '               and a clean pass, using a synthetic runner stub; exit\n'
    );
    return 0;
  }

  if (args.has('--list')) {
    for (const ns of ISOLATION_NAMESPACES) process.stdout.write(ns + '\n');
    return 0;
  }

  if (args.has('--self-test')) {
    return runSelfTest();
  }

  const implDir = implementationDir();

  if (args.has('--compile')) {
    if (!compileBundle(implDir)) return 2;
  }

  const bundlePath = path.join(implDir, NODE_TEST_BUNDLE);
  const baseline = bundleFingerprint(bundlePath);
  if (baseline === null) {
    process.stderr.write(
      `error: ${NODE_TEST_BUNDLE} not found. Run \`npm run test:cljs\` (or pass ` +
        '--compile) first so the consolidated bundle exists.\n'
    );
    return 2;
  }

  const results = [];
  let bundleMoved = false;
  for (const ns of ISOLATION_NAMESPACES) {
    process.stderr.write(`  isolating ${ns} ... `);
    const run = runNamespaceAlone(implDir, ns);
    // Re-stat after EVERY run, not only the red ones: the move that
    // invalidates the sweep can land between two greens just as easily.
    if (!sameBundle(baseline, bundleFingerprint(bundlePath))) bundleMoved = true;
    const verdict = classifyRun(run, { bundleMoved });
    const counts = verdict.summary
      ? ` (${verdict.summary.tests} tests, ${verdict.summary.assertions} assertions)`
      : '';
    process.stderr.write(
      verdict.kind === 'green' ? `ok${counts}\n` : `${verdict.kind.toUpperCase()}\n`
    );
    results.push({ ns, run, verdict });
  }

  const of = (kind) => results.filter((r) => r.verdict.kind === kind);
  const environment = of('environment');
  const roster = of('roster');
  const isolation = of('isolation');

  if (environment.length === 0 && roster.length === 0 && isolation.length === 0) {
    const totals = results.reduce(
      (acc, r) => ({
        tests: acc.tests + r.verdict.summary.tests,
        assertions: acc.assertions + r.verdict.summary.assertions,
      }),
      { tests: 0, assertions: 0 }
    );
    process.stderr.write(
      `\nper-ns isolation: all ${ISOLATION_NAMESPACES.length} namespace(s) ` +
        `pass standalone (${totals.tests} tests, ${totals.assertions} ` +
        'assertions in total).\n'
    );
    return 0;
  }

  const detail = (entry) => {
    const { ns, run, verdict } = entry;
    process.stderr.write(`  ${verdict.kind.toUpperCase()}: ${ns} (exit=${run.status})\n`);
    if (verdict.reason) process.stderr.write(`      ${verdict.reason}\n`);
    // Surface the tail of the diagnostic (the adapter / fixture error).
    const tail = (run.stdout + run.stderr)
      .split('\n')
      .filter((l) => l.trim() !== '')
      .slice(-6)
      .map((l) => '      ' + l)
      .join('\n');
    if (tail) process.stderr.write(tail + '\n');
  };

  if (environment.length > 0) {
    process.stderr.write(
      `\nper-ns isolation: INDETERMINATE — ${environment.length} of ` +
        `${ISOLATION_NAMESPACES.length} run(s) never became a verdict about ` +
        'their namespace:\n\n'
    );
    environment.forEach(detail);
    process.stderr.write(
      '\nThis is NOT a finding about these namespaces, and the sweep\'s green ' +
        'runs are no more trustworthy than its red ones.\n' +
        `A ${NODE_TEST_BUNDLE} that is missing, half-written, or rebuilt ` +
        'underneath the sweep fails every run still to come, which is why the ' +
        'reds tend to arrive as a contiguous tail.\n' +
        'What to do:\n' +
        '  * Let any concurrent build finish — a compile of a :node-test-family ' +
        'build UNLINKS out/node-test.js before it starts (rf2-6t03c), so one ' +
        'starting mid-sweep pulls the bundle out from under this gate.\n' +
        '  * Then rerun: `npm run test:cljs-isolation` (compile + sweep, ' +
        'nothing else building).\n' +
        '  * A red that survives a quiet rerun is real — report it with the ' +
        'per-namespace output above.\n'
    );
    return 2;
  }

  if (isolation.length > 0) {
    process.stderr.write(
      `\n${isolation.length} namespace(s) FAILED when run in isolation (they ` +
        'pass in the consolidated bundle ONLY because a sibling left runtime ' +
        'state — e.g. an installed adapter — behind):\n\n'
    );
    isolation.forEach(detail);
    process.stderr.write(
      '\nFix:\n  * Give the failing namespace a fixture that installs its OWN ' +
        'substrate adapter and resets runtime state — e.g.\n' +
        '    (use-fixtures :each\n' +
        '      (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))\n' +
        '  Do NOT rely on a sibling test having installed one (consolidated-bundle ' +
        'ordering is not a contract).\n'
    );
    if (roster.length > 0) {
      process.stderr.write(
        `\nAlso: ${roster.length} roster entry/entries below match no test var ` +
          '— see the ROSTER section policy in the next paragraph.\n'
      );
      roster.forEach(detail);
    }
    return 1;
  }

  process.stderr.write(
    `\n${roster.length} roster entry/entries name a namespace that no longer ` +
      'exists, so this gate silently stopped checking it:\n\n'
  );
  roster.forEach(detail);
  process.stderr.write(
    '\nFix:\n  * Update ISOLATION_NAMESPACES in this file — the namespace was ' +
      'renamed or deleted.\n' +
      '  * If it was RENAMED, carry the entry over rather than dropping it: the ' +
      'fixture it guards is unchanged, and an unguarded fixture can silently ' +
      're-acquire the dependency this gate exists to forbid.\n'
  );
  return 2;
}

// ---------------------------------------------------------------------------
// Self-test — prove the gate's red/green logic with a synthetic runner stub
// (no shadow-cljs compile required). We exercise the same exit-code contract
// the real run depends on: a non-zero standalone exit for ONE namespace makes
// the gate fail; an all-zero set passes.
// ---------------------------------------------------------------------------
function runSelfTest() {
  let failures = 0;
  const assert = (cond, msg) => {
    if (cond) {
      process.stderr.write(`self-test PASS: ${msg}\n`);
    } else {
      process.stderr.write(`self-test FAIL: ${msg}\n`);
      failures += 1;
    }
  };

  const run = (over) => ({ status: 1, spawnError: null, stdout: '', stderr: '', ...over });
  const kindOf = (over, opts) => classifyRun(run(over), opts || {}).kind;

  // --- Output shapes below are TRANSCRIBED from real runs of this bundle,
  // --- not invented, so the classifier is pinned against what the runner
  // --- actually emits (rf2-v8561).

  // 1. GREEN — the cljs.test summary re-frame.test-quiet closes every
  //    completed run with.
  const GREEN_OUT = '\nRan 3 tests containing 4 assertions.\n0 failures, 0 errors.\n';
  assert(kindOf({ status: 0, stdout: GREEN_OUT }) === 'green',
    'a zero exit carrying a non-zero test summary is GREEN');
  const greenSummary = classifyRun(run({ status: 0, stdout: GREEN_OUT }), {}).summary;
  assert(greenSummary && greenSummary.tests === 3 && greenSummary.assertions === 4,
    'the summary counts are parsed off the run (3 tests, 4 assertions)');

  // 2. ISOLATION — tests RAN and failed. This is the only shape that earns
  //    the fixture remedy, and rf2-oslyz's no-adapter errors are its
  //    archetype.
  const ISOLATION_OUT =
    '\nTesting re-frame.story.open-in-editor-ownership-cljs-test\n' +
    'ERROR in (open-in-editor-ownership) :rf.error/no-adapter-installed\n' +
    '\nRan 5 tests containing 5 assertions.\n0 failures, 5 errors.\n';
  assert(kindOf({ status: 1, stdout: ISOLATION_OUT }) === 'isolation',
    'a non-zero exit that still reported a test summary is a real ISOLATION red');

  // 3. ROSTER — the runner refused an unmatched selector. Verbatim from
  //    `node out/node-test.js --test=re-frame.this-ns-does-not-exist-cljs-test`.
  const ROSTER_OUT =
    'ERROR: no tests matched --test= selector(s): re-frame.nope-cljs-test\n' +
    'Use --list to see known test names.\n';
  assert(kindOf({ status: 1, stdout: ROSTER_OUT }) === 'roster',
    'an unmatched selector is a stale ROSTER entry, not a fixture defect');

  // 4. ENVIRONMENT — the bundle was gone. Verbatim from a run against a
  //    deleted `:output-to`, which is exactly what a concurrent
  //    `compile-node-test.cjs` leaves behind while it works (rf2-6t03c).
  const MISSING_BUNDLE_OUT =
    'node:internal/modules/cjs/loader:1424\n  throw err;\n  ^\n\n' +
    "Error: Cannot find module 'C:\\...\\implementation\\out\\node-test.js'\n";
  assert(kindOf({ status: 1, stderr: MISSING_BUNDLE_OUT }) === 'environment',
    'a module-load crash with no test summary is ENVIRONMENT, not isolation');

  // 5. THE DISCRIMINATION THE GATE EXISTS TO MAKE (rf2-v8561): the same
  //    non-zero exit is a fixture accusation ONLY when the run got far
  //    enough to be one. Module load links EVERY test namespace, so a crash
  //    before the summary cannot single one out.
  assert(kindOf({ status: 1, stdout: ISOLATION_OUT }) !== kindOf({ status: 1, stderr: MISSING_BUNDLE_OUT }),
    'summary-present and summary-absent reds classify DIFFERENTLY (the whole point)');

  // 6. A moved bundle invalidates the sweep wholesale — a run that LOOKS
  //    green is no more trustworthy than one that looks red.
  assert(kindOf({ status: 0, stdout: GREEN_OUT }, { bundleMoved: true }) === 'environment',
    'once the bundle moves, even a green run is ENVIRONMENT (sweep indeterminate)');
  assert(kindOf({ status: 1, stdout: ISOLATION_OUT }, { bundleMoved: true }) === 'environment',
    'once the bundle moves, a summary-bearing red is no longer a fixture verdict');

  // 7. A spawn that never produced a process, and one killed before it could
  //    report, are both ENVIRONMENT.
  assert(kindOf({ status: null, spawnError: 'ENOENT' }) === 'environment',
    'a failed spawn is ENVIRONMENT');
  assert(kindOf({ status: null }) === 'environment',
    'a killed process (null exit) is ENVIRONMENT, never a silent pass');

  // 8. A green that ran nothing certifies nothing.
  const EMPTY_GREEN = 'Ran 0 tests containing 0 assertions.\n0 failures, 0 errors.\n';
  assert(kindOf({ status: 0, stdout: EMPTY_GREEN }) === 'environment',
    'a zero-test green is refused, not counted as coverage');
  assert(kindOf({ status: 0, stdout: 'no summary here\n' }) === 'environment',
    'a zero exit with no summary at all is refused');

  // 9. Bundle fingerprinting: identity, difference, and absence.
  assert(sameBundle({ size: 1, mtimeMs: 2 }, { size: 1, mtimeMs: 2 }),
    'identical size+mtime is the same bundle');
  assert(!sameBundle({ size: 1, mtimeMs: 2 }, { size: 1, mtimeMs: 3 }),
    'a rewritten bundle (same size, new mtime) is detected as moved');
  assert(!sameBundle({ size: 1, mtimeMs: 2 }, null),
    'a bundle that vanished mid-sweep is detected as moved');
  assert(bundleFingerprint(path.join(implementationDir(), 'no-such-file.js')) === null,
    'fingerprinting an absent file yields null rather than throwing');

  // The curated list is non-empty and dedup'd (a typo'd duplicate would run
  // twice and silently weaken the signal).
  const uniq = new Set(ISOLATION_NAMESPACES);
  assert(ISOLATION_NAMESPACES.length > 0, 'curated namespace list is non-empty');
  assert(uniq.size === ISOLATION_NAMESPACES.length,
    'curated namespace list has no duplicates');

  if (failures) {
    process.stderr.write(`\n${failures} self-test failure(s).\n`);
    return 1;
  }
  process.stderr.write('\nall self-tests passed.\n');
  return 0;
}

process.exit(main(process.argv.slice(2)));
