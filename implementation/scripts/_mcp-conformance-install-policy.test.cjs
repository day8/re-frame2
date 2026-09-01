#!/usr/bin/env node

'use strict';

/*
 * Policy gate for `test-mcp-conformance.cjs` — two concerns, one file.
 *
 *   A. Reproducible-install policy (rf2-vtp2er), below.
 *   B. Profile SELECTION (rf2-a9l6e): default vs `--live` vs `--help` vs an
 *      unknown option, and the verdict text each one renders. These are
 *      pure-function assertions against the runner's exported
 *      `parseArgs` / `planRun` / `renderReport` / `helpText`, so the suite
 *      launches no Clojure, no shadow-cljs, no Chromium, and neither MCP
 *      server — the runner only spawns under `require.main === module`,
 *      which this section pins.
 *
 * They share a file rather than splitting because both pin the SAME script
 * and both are static/pure; a second `_*.test.cjs` would only duplicate the
 * fixture.
 *
 * --- A. Reproducible-install policy (rf2-vtp2er) ---
 *
 * `npm run test:mcp-conformance` must be a (near-)pure verification
 * command: it must not silently re-mutate dependency state on every run,
 * and where a tool package ships a committed package-lock.json the runner
 * must install lockfile-exact via `npm ci` (which FAILS on package.json /
 * lock drift) rather than `npm install` (which resolves semver ranges
 * against the live registry and can rewrite the lock).
 *
 * This is a STATIC source-policy gate (it reads the runner source + the
 * on-disk lockfile state; it does NOT spawn npm). It pins three
 * invariants so a future edit can't quietly regress the posture:
 *
 *   1. Install prep steps are declared via the `install: true` marker and
 *      routed through `resolveInstallStep`, NOT hard-coded as
 *      `args: ['install']`. (The marker is what lets the runner pick the
 *      most reproducible install per-package at run time.)
 *   2. `resolveInstallStep` chooses `npm ci` when a committed lockfile is
 *      present, and only ever falls back to `npm install` when there is no
 *      committed lockfile.
 *   3. LIVE: every tool package this runner installs that currently ships
 *      a committed package-lock.json resolves to `npm ci` (and a clean
 *      `npm ci --dry-run`-able lock). A package without a committed lock
 *      (a deliberately lock-gitignored published package) is exempt and
 *      may bootstrap via a skip-if-present `npm install`.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
// Shared stripComments (EXECUTABLE-source-only matching) + framework-free
// test harness (rf2-j552l2). perTestPass:true preserves this suite's
// per-test `PASS  <name>` line.
const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
const RUNNER_PATH = path.join(SCRIPTS_DIR, 'test-mcp-conformance.cjs');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const TOOLS = path.join(REPO_ROOT, 'tools');

const { test, run } = createPolicyTestSuite('mcp-conformance-install-policy', { perTestPass: true });

const RUNNER_SRC = fs.readFileSync(RUNNER_PATH, 'utf8');
const RUNNER_CODE = stripComments(RUNNER_SRC);

// 1. Install steps are declared via `install: true` + resolved through
//    resolveInstallStep — not hard-coded `args: ['install']`.
test('test-mcp-conformance.cjs declares install prep via the install marker', () => {
  assert.match(
    RUNNER_CODE,
    /\binstall:\s*true\b/,
    "expected install prep steps declared with `install: true` so the " +
      'runner can pick npm ci / npm install per-package reproducibly.',
  );
});

test('test-mcp-conformance.cjs routes install steps through resolveInstallStep', () => {
  assert.match(
    RUNNER_CODE,
    /resolveInstallStep\s*\(/,
    'expected the run loop to resolve install steps via resolveInstallStep.',
  );
  assert.match(
    RUNNER_CODE,
    /function\s+resolveInstallStep\b/,
    'expected resolveInstallStep to be defined in the runner.',
  );
});

test('test-mcp-conformance.cjs does NOT hard-code npm install args in a STEPS prep entry', () => {
  // A literal `args: ['install']` inside a STEPS entry (npm install
  // hard-coded as a prep step) is exactly the unpinned mutation this gate
  // forbids — install prep must flow through resolveInstallStep, which
  // prefers `npm ci` where a lockfile exists. The ONE legitimate
  // `args: ['install']` occurrence is the first-run bootstrap fallback
  // INSIDE resolveInstallStep (gated on "no committed lock AND no
  // node_modules"); exclude that function body before scanning.
  const codeOutsideResolver = RUNNER_CODE.replace(
    /function\s+resolveInstallStep\b[\s\S]*?\n\}/,
    '/* resolveInstallStep body elided for this scan */',
  );
  assert.doesNotMatch(
    codeOutsideResolver,
    /args:\s*\[\s*['"]install['"]\s*\]/,
    "test-mcp-conformance.cjs hard-codes `args: ['install']` for a prep " +
      'step — declare it `install: true` and let resolveInstallStep pick ' +
      'the reproducible command (rf2-vtp2er).',
  );
  // Sanity: the elision actually removed the resolver (so we didn't just
  // pass because the regex failed to find the function).
  assert.ok(
    codeOutsideResolver.includes('resolveInstallStep body elided'),
    'expected the resolveInstallStep body to be elided before the scan.',
  );
});

// 2. resolveInstallStep prefers `npm ci` when a lockfile is present.
test('resolveInstallStep selects npm ci when a committed lockfile is present', () => {
  // The ci branch is gated on the committed-lockfile predicate and must
  // emit `['ci']`. Match both the predicate and the ci args near it.
  assert.match(
    RUNNER_CODE,
    /hasCommittedLockfile\s*\(/,
    'resolveInstallStep must branch on a committed-lockfile predicate.',
  );
  assert.match(
    RUNNER_CODE,
    /args:\s*\[\s*['"]ci['"]\s*\]/,
    'resolveInstallStep must install lockfile-backed packages via `npm ci`.',
  );
});

// 3. LIVE: every tool package the runner installs that ships a committed
//    package-lock.json must end up on `npm ci`, and that lock must be a
//    clean `npm ci --dry-run`-able lock (no drift). Packages WITHOUT a
//    committed lock are exempt (deliberately lock-gitignored published
//    packages bootstrap via skip-if-present npm install).
//
// We re-derive the package set from the runner source rather than
// hard-coding it: any `cwd: <CONST>` paired with an `install: true` step
// names a package the runner installs.
const TOOL_PKG_CONSTS = {
  PAIR_MCP: path.join(TOOLS, 're-frame2-pair-mcp'),
  CONFORMANCE: path.join(TOOLS, 'mcp-conformance'),
  STORY_MCP: path.join(TOOLS, 'story-mcp'),
};

function installedPkgDirs() {
  // Find `{ ... install: true, ... cwd: <CONST>, ... }` blocks. The STEPS
  // entries put `install: true` and `cwd: <CONST>` within a few lines of
  // each other; scan for the cwd const that accompanies an install marker.
  const dirs = new Set();
  const stepRe = /\{[^}]*\binstall:\s*true\b[^}]*\}/g;
  let m;
  while ((m = stepRe.exec(RUNNER_CODE)) !== null) {
    const block = m[0];
    const cwdMatch = block.match(/cwd:\s*([A-Z_]+)/);
    if (cwdMatch && TOOL_PKG_CONSTS[cwdMatch[1]]) {
      dirs.add(TOOL_PKG_CONSTS[cwdMatch[1]]);
    }
  }
  return [...dirs];
}

test('LIVE: lockfile-backed tool packages the runner installs have a present, drift-free lock', () => {
  const pkgDirs = installedPkgDirs();
  assert.ok(
    pkgDirs.length > 0,
    'expected to derive at least one install-prep package dir from the runner source.',
  );
  let checkedLocked = 0;
  for (const pkgDir of pkgDirs) {
    const lock = path.join(pkgDir, 'package-lock.json');
    if (!fs.existsSync(lock)) {
      // No committed lock — exempt (bootstrap-only npm install path).
      continue;
    }
    checkedLocked += 1;

    // Hermetic structural drift check (no network, no npm spawn): npm ci
    // refuses to run when package.json's declared deps are not all present
    // in the lockfile's root-package dep map. Assert that invariant
    // directly so the gate is deterministic on offline CI rather than
    // depending on registry reachability.
    const pkg = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8'));
    const lockJson = JSON.parse(fs.readFileSync(lock, 'utf8'));
    const rootPkg = (lockJson.packages && lockJson.packages['']) || {};
    const lockedRootDeps = {
      ...(rootPkg.dependencies || {}),
      ...(rootPkg.devDependencies || {}),
      ...(rootPkg.optionalDependencies || {}),
    };
    const declared = {
      ...(pkg.dependencies || {}),
      ...(pkg.devDependencies || {}),
      ...(pkg.optionalDependencies || {}),
    };
    for (const [name, range] of Object.entries(declared)) {
      assert.ok(
        Object.prototype.hasOwnProperty.call(lockedRootDeps, name),
        `${path.relative(REPO_ROOT, pkgDir)}: package.json declares "${name}" ` +
          'but package-lock.json does not record it for the root package — ' +
          'package.json and the lock have drifted. The runner installs this ' +
          'package via `npm ci`, which would FAIL. Re-run `npm install` and ' +
          'commit the refreshed lock (rf2-vtp2er).',
      );
      assert.equal(
        lockedRootDeps[name],
        range,
        `${path.relative(REPO_ROOT, pkgDir)}: package.json declares "${name}": ` +
          `"${range}" but the lock records "${lockedRootDeps[name]}" — drift. ` +
          'The runner installs via `npm ci`, which would FAIL. Re-sync the lock.',
      );
    }

    // Belt-and-braces: a clean `npm ci --dry-run` is the authoritative
    // drift oracle. Run it opportunistically, but only FAIL on the
    // definitive lock-mismatch signal — never on a network/registry error
    // (so this gate stays green on offline CI). cp/the dry-run is the only
    // network-touching line in the suite; everything above is hermetic.
    const res = cp.spawnSync(
      process.platform === 'win32' ? 'npm.cmd' : 'npm',
      ['ci', '--dry-run', '--no-audit', '--no-fund'],
      { cwd: pkgDir, encoding: 'utf8' },
    );
    if (res.error || res.status == null) {
      // npm unavailable / spawn failure — structural check above stands.
      continue;
    }
    if (res.status !== 0) {
      const stderr = String(res.stderr || '');
      const isDrift =
        /can only install packages when your package\.json and package-lock\.json/i.test(stderr) ||
        /lock file('?s)? .*(out of sync|missing)/i.test(stderr) ||
        /Missing:.*from lock file/i.test(stderr);
      assert.ok(
        !isDrift,
        `npm ci --dry-run reported lock drift in ${path.relative(REPO_ROOT, pkgDir)} ` +
          `— the runner's npm ci would fail. Re-sync the lock.\nstderr:\n${stderr}`,
      );
      // Non-drift non-zero (e.g. offline registry error) — tolerate.
    }
  }
  assert.ok(
    checkedLocked > 0,
    'expected at least one lockfile-backed tool package (mcp-conformance) ' +
      'to be exercised by this gate.',
  );
});

// ---------------------------------------------------------------------
// B. Profile selection + verdict honesty (rf2-a9l6e).
//
// Requiring the runner is itself part of the contract: it must expose its
// selection/rendering as pure functions and spawn NOTHING at module load.
// If that regressed, this `require` would start installing npm packages and
// booting servers, and the suite would not finish.
const runner = require('./test-mcp-conformance.cjs');

test('the runner only spawns under require.main === module', () => {
  assert.match(
    RUNNER_CODE,
    /require\.main\s*===\s*module/,
    'expected the run loop to be guarded by `require.main === module` so ' +
      'the profile-selection tests can require the runner without launching ' +
      'Clojure, shadow-cljs, Chromium, or either MCP server (rf2-a9l6e).',
  );
});

test('default selection: no flag selects the default profile', () => {
  assert.deepEqual(runner.parseArgs([]), {
    help: false,
    profile: runner.PROFILE_DEFAULT,
    error: null,
  });
});

test('--live selects the live profile', () => {
  assert.deepEqual(runner.parseArgs(['--live']), {
    help: false,
    profile: runner.PROFILE_LIVE,
    error: null,
  });
});

test('--help / -h select help, and help is side-effect-free text', () => {
  for (const flag of ['--help', '-h']) {
    const parsed = runner.parseArgs([flag]);
    assert.equal(parsed.help, true, `${flag} must select help`);
    assert.equal(parsed.error, null, `${flag} must not be a usage error`);
  }
  const help = runner.helpText();
  // Both modes, the cost boundary, the prerequisites, which mode proves
  // live runtime behaviour, and the narrower meaning of the conformance
  // package's own `npm test` (acceptance 3).
  for (const needle of [
    runner.ROOT_COMMAND,
    runner.LIVE_COMMAND,
    'default (medium)',
    '--live (expensive)',
    'PREREQUISITES',
    'PROFILE THAT PROVES LIVE RUNTIME SEMANTICS',
    'NARROWER NEIGHBOUR',
    'EXIT CODES',
  ]) {
    assert.ok(
      help.includes(needle),
      `--help output must mention ${JSON.stringify(needle)}.`,
    );
  }
});

test('an unknown option is a usage error naming the option, with no profile', () => {
  const parsed = runner.parseArgs(['--liv']);
  assert.equal(parsed.profile, null, 'a usage error must select no profile');
  assert.equal(parsed.help, false);
  assert.match(
    String(parsed.error),
    /unknown option `--liv`/,
    'the usage error must name the offending option.',
  );
  // The usage exit code must not collide with the gate codes (1 = gate /
  // inner-conformance failure, 2 = hermetic orchestration/cleanup failure).
  assert.notEqual(runner.EXIT_USAGE, 0);
  assert.notEqual(runner.EXIT_USAGE, 1);
  assert.notEqual(runner.EXIT_USAGE, 2);
});

test('the live gate is ABSENT from the default plan and PRESENT in the live plan', () => {
  const dflt = runner.planRun(runner.PROFILE_DEFAULT);
  const live = runner.planRun(runner.PROFILE_LIVE);
  assert.equal(dflt.gates.length, runner.DEFAULT_GATES.length);
  assert.equal(live.gates.length, runner.DEFAULT_GATES.length + 1);
  assert.ok(
    !dflt.gates.includes(runner.LIVE_GATE),
    'the default profile must not plan the hermetic live gate.',
  );
  assert.equal(
    live.gates[live.gates.length - 1],
    runner.LIVE_GATE,
    'the live profile must append the hermetic live gate last.',
  );
  // Both profiles share the same prerequisites; --live adds a gate, not a
  // second orchestrator.
  assert.deepEqual(dflt.prep, live.prep);
});

test('the live gate delegates to the EXISTING hermetic entry point (no second roster)', () => {
  const conformancePkg = JSON.parse(
    fs.readFileSync(path.join(TOOLS, 'mcp-conformance', 'package.json'), 'utf8'),
  );
  const script = conformancePkg.scripts['test:re-frame2-pair-live-hermetic-suite'];
  assert.ok(
    script,
    'tools/mcp-conformance/package.json must keep the ' +
      'test:re-frame2-pair-live-hermetic-suite script the --live profile delegates to.',
  );
  assert.ok(
    script.includes(runner.LIVE_GATE.args[0]),
    `the --live gate runs ${runner.LIVE_GATE.args[0]}, which must be the same ` +
      `entry point as the package script (${script}).`,
  );
  assert.equal(runner.LIVE_GATE.node, true, 'the live gate spawns under node');
  // The live row count comes from the one live roster, never a copy.
  assert.match(
    RUNNER_CODE,
    /require\([\s\S]{0,120}?live-test-inventory\.cjs['"]/,
    'the runner must read LIVE_TESTS from ' +
      'tools/mcp-conformance/scripts/live-test-inventory.cjs rather than ' +
      'listing live rows itself (rf2-a9l6e acceptance 6).',
  );
});

// A green that cannot be told apart from a skip is the defect rf2-a9l6e
// exists to close, so the verdict text is pinned as tightly as the plan.
function reportFor(profile, fail = null) {
  const { gates } = runner.planRun(profile);
  const results = gates.map((g) => ({ name: g.name, status: 0 }));
  if (!fail) {
    return runner.renderReport({ profile, gates, results, firstFailure: null });
  }
  return runner.renderReport({
    profile,
    gates,
    // Fail-fast: the failing step's row, and nothing after it.
    results: results
      .slice(0, fail.index)
      .concat([{ name: gates[fail.index].name, status: fail.status }]),
    firstFailure: {
      step: gates[fail.index].name,
      status: fail.status,
      signal: null,
    },
  });
}

test('the default-profile verdict names its profile and marks the live suite NOT RUN', () => {
  const report = reportFor(runner.PROFILE_DEFAULT);
  assert.match(
    report,
    /MCP-CONFORMANCE DEFAULT PROFILE GREEN — 6\/6 gates, hermetic live Pair suite NOT RUN/,
  );
  assert.ok(
    report.includes('[NOT RUN ]'),
    'the default summary must carry an explicit NOT RUN row for the hermetic suite.',
  );
  assert.ok(
    report.includes(runner.LIVE_COMMAND),
    'the default summary must print the exact --live invocation beside it.',
  );
});

test('no unqualified all-green sentence survives anywhere in the runner', () => {
  // The old sentinel. Renaming it is only worth anything if it cannot come
  // back — pin the source AND the rendered default-profile report.
  const forbidden = 'ALL MCP-CONFORMANCE GATES GREEN';
  // RUNNER_CODE, not RUNNER_SRC: the header comment legitimately QUOTES the
  // retired sentinel to explain why it went. Comments are stripped so the
  // pin matches executable source only.
  assert.ok(
    !RUNNER_CODE.includes(forbidden),
    `test-mcp-conformance.cjs must not emit "${forbidden}": it cannot be ` +
      'told apart from a run whose live layer was skipped (rf2-a9l6e).',
  );
  const report = reportFor(runner.PROFILE_DEFAULT);
  assert.ok(!report.includes(forbidden));
  // Control: the pin is a real substring test, not a pattern that can only
  // ever answer "no match" — the same shape IS found where it exists.
  assert.ok(reportFor(runner.PROFILE_DEFAULT).includes('PROFILE GREEN'));
});

test('the live-profile verdict names its profile and carries no NOT RUN row', () => {
  const report = reportFor(runner.PROFILE_LIVE);
  assert.match(
    report,
    /MCP-CONFORMANCE LIVE PROFILE GREEN — 7\/7 gates, hermetic live Pair suite INCLUDED/,
  );
  assert.ok(
    !report.includes('NOT RUN'),
    'the live profile ran the hermetic suite — nothing may be marked NOT RUN.',
  );
  assert.ok(
    report.includes(runner.LIVE_GATE.name),
    'the live summary must show the hermetic gate as one of its rows.',
  );
});

test('a failing gate renders a profile-named FAILED verdict in both profiles', () => {
  const dflt = reportFor(runner.PROFILE_DEFAULT, { index: 2, status: 1 });
  assert.match(dflt, /MCP-CONFORMANCE DEFAULT PROFILE FAILED — .* exited 1/);
  assert.ok(!dflt.includes('PROFILE GREEN'));
  assert.ok(
    dflt.includes('halted by an earlier failure'),
    'gates the fail-fast never reached must be labelled halted, never passed.',
  );

  // The hermetic suite's own 1-vs-2 distinction must survive to the verdict:
  // 2 is an orchestration/cleanup failure, not an inner conformance failure.
  const liveGates = runner.planRun(runner.PROFILE_LIVE).gates;
  const live = reportFor(runner.PROFILE_LIVE, {
    index: liveGates.indexOf(runner.LIVE_GATE),
    status: 2,
  });
  assert.match(live, /MCP-CONFORMANCE LIVE PROFILE FAILED — .* exited 2/);
  assert.ok(!live.includes('PROFILE GREEN'));
});

run();
