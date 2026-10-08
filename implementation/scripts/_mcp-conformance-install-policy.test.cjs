#!/usr/bin/env node

'use strict';

/*
 * Policy tests for `test-mcp-conformance.cjs`.
 *
 * A. Reproducible install: a tool package with a committed package-lock.json is
 *    installed with `npm ci` (which fails on package.json / lock drift), never a
 *    hard-coded `npm install` (which can rewrite the lock), and those locks are
 *    drift-free. Static over the runner source and the on-disk locks.
 * B. Profile selection and verdict honesty: default vs `--live` vs `--help` vs an
 *    unknown option, and the verdict each renders, via the runner's pure exports.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const cp = require('child_process');
const fs = require('fs');
const path = require('path');
const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const SCRIPTS_DIR = __dirname;
const RUNNER_PATH = path.join(SCRIPTS_DIR, 'test-mcp-conformance.cjs');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const TOOLS = path.join(REPO_ROOT, 'tools');

const { test, run } = createPolicyTestSuite('mcp-conformance-install-policy', { perTestPass: true });

const RUNNER_SRC = fs.readFileSync(RUNNER_PATH, 'utf8');
const RUNNER_CODE = stripComments(RUNNER_SRC);

test('test-mcp-conformance.cjs does NOT hard-code npm install args in a STEPS prep entry', () => {
  // The one legitimate `args: ['install']` is the no-lock bootstrap inside
  // resolveInstallStep, so that body is elided before the scan.
  const codeOutsideResolver = RUNNER_CODE.replace(
    /function\s+resolveInstallStep\b[\s\S]*?\n\}/,
    '/* resolveInstallStep body elided for this scan */',
  );
  assert.doesNotMatch(
    codeOutsideResolver,
    /args:\s*\[\s*['"]install['"]\s*\]/,
    "test-mcp-conformance.cjs hard-codes `args: ['install']` for a prep " +
      'step — declare it `install: true` and let resolveInstallStep pick ' +
      'the reproducible command.',
  );
});

test('resolveInstallStep selects npm ci when a committed lockfile is present', () => {
  assert.match(
    RUNNER_CODE,
    /args:\s*\[\s*['"]ci['"]\s*\]/,
    'resolveInstallStep must install lockfile-backed packages via `npm ci`.',
  );
});

// The installed package set is derived from the runner: a `cwd: <CONST>` beside
// an `install: true` marker. A package without a committed lock is exempt.
const TOOL_PKG_CONSTS = {
  PAIR_MCP: path.join(TOOLS, 're-frame2-pair-mcp'),
  CONFORMANCE: path.join(TOOLS, 'mcp-conformance'),
  STORY_MCP: path.join(TOOLS, 'story-mcp'),
};

function installedPkgDirs() {
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
  let checkedLocked = 0;
  for (const pkgDir of installedPkgDirs()) {
    const lock = path.join(pkgDir, 'package-lock.json');
    if (!fs.existsSync(lock)) continue;
    checkedLocked += 1;

    // Hermetic: npm ci refuses when package.json's declared deps are not all
    // recorded for the lock's root package.
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
      assert.equal(
        lockedRootDeps[name],
        range,
        `${path.relative(REPO_ROOT, pkgDir)}: package.json declares "${name}": ` +
          `"${range}" but the lock records "${lockedRootDeps[name]}" — drift. ` +
          'The runner installs via `npm ci`, which would FAIL. Re-sync the lock.',
      );
    }

    // `npm ci --dry-run` sees drift the root-dep check cannot; it fails only on
    // the definitive lock-mismatch signal, never on a registry error.
    const res = cp.spawnSync(
      process.platform === 'win32' ? 'npm.cmd' : 'npm',
      ['ci', '--dry-run', '--no-audit', '--no-fund'],
      { cwd: pkgDir, encoding: 'utf8' },
    );
    if (res.error || res.status == null) continue;
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
    }
  }
  assert.ok(
    checkedLocked > 0,
    'expected at least one lockfile-backed tool package (mcp-conformance) ' +
      'to be exercised by this gate.',
  );
});

// ---- B. Profile selection and verdict honesty ----
//
// Requiring the runner must spawn nothing: the run loop sits behind
// `require.main === module`.
const runner = require('./test-mcp-conformance.cjs');

test('no flag selects the default profile and --live the live profile', () => {
  assert.deepEqual(
    [[], ['--live']].map((argv) => runner.parseArgs(argv)),
    [
      { help: false, profile: runner.PROFILE_DEFAULT, error: null },
      { help: false, profile: runner.PROFILE_LIVE, error: null },
    ],
  );
});

test('--help / -h select help, and help names both commands', () => {
  assert.deepEqual(
    ['--help', '-h'].map((flag) => {
      const { help, error } = runner.parseArgs([flag]);
      return { help, error };
    }),
    [{ help: true, error: null }, { help: true, error: null }],
  );
  const help = runner.helpText();
  assert.ok(
    help.includes(runner.ROOT_COMMAND) && help.includes(runner.LIVE_COMMAND),
    '--help output must name both the default and the --live command.',
  );
});

test('an unknown option is a usage error naming the option, with no profile', () => {
  const { profile, help, error } = runner.parseArgs(['--liv']);
  assert.deepEqual({ profile, help }, { profile: null, help: false });
  assert.match(String(error), /unknown option `--liv`/);
  // 1 is a gate / inner-conformance failure and 2 an orchestration failure.
  assert.ok(![0, 1, 2].includes(runner.EXIT_USAGE), `EXIT_USAGE ${runner.EXIT_USAGE} collides with a gate code`);
});

test('the live gate is ABSENT from the default plan and PRESENT in the live plan', () => {
  const dflt = runner.planRun(runner.PROFILE_DEFAULT);
  const live = runner.planRun(runner.PROFILE_LIVE);
  assert.ok(!dflt.gates.includes(runner.LIVE_GATE), 'the default profile must not plan the hermetic live gate.');
  // --live appends the gate last and shares the prerequisites: a gate, not a second orchestrator.
  assert.deepEqual(live.gates, [...dflt.gates, runner.LIVE_GATE]);
  assert.deepEqual(dflt.prep, live.prep);
});

test('the live gate delegates to the EXISTING hermetic entry point (no second roster)', () => {
  const conformancePkg = JSON.parse(
    fs.readFileSync(path.join(TOOLS, 'mcp-conformance', 'package.json'), 'utf8'),
  );
  const script = conformancePkg.scripts['test:re-frame2-pair-live-hermetic-suite'];
  assert.ok(
    script.includes(runner.LIVE_GATE.args[0]),
    `the --live gate runs ${runner.LIVE_GATE.args[0]}, which must be the same ` +
      `entry point as the package script (${script}).`,
  );
  // The live row count comes from the one live roster, never a copy.
  assert.match(
    RUNNER_CODE,
    /require\([\s\S]{0,120}?live-test-inventory\.cjs['"]/,
    'the runner must read LIVE_TESTS from ' +
      'tools/mcp-conformance/scripts/live-test-inventory.cjs rather than ' +
      'listing live rows itself.',
  );
});

// A green that cannot be told apart from a skip is the defect this section
// exists to prevent, so the verdict text is pinned as tightly as the plan.
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

// A comment may quote the sentinel, so the executable source is what is read.
test('no unqualified all-green sentence survives anywhere in the runner', () => {
  const forbidden = 'ALL MCP-CONFORMANCE GATES GREEN';
  assert.ok(
    !RUNNER_CODE.includes(forbidden),
    `test-mcp-conformance.cjs must not emit "${forbidden}": it cannot be ` +
      'told apart from a run whose live layer was skipped.',
  );
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

  // The hermetic suite's 1-vs-2 distinction must survive to the verdict.
  const liveGates = runner.planRun(runner.PROFILE_LIVE).gates;
  const live = reportFor(runner.PROFILE_LIVE, {
    index: liveGates.indexOf(runner.LIVE_GATE),
    status: 2,
  });
  assert.match(live, /MCP-CONFORMANCE LIVE PROFILE FAILED — .* exited 2/);
  assert.ok(!live.includes('PROFILE GREEN'));
});

run();
