#!/usr/bin/env node

'use strict';

/*
 * Policy tests for `test-mcp-conformance.cjs`.
 *
 * A. Reproducible install: a tool package with a committed package-lock.json is
 *    installed with `npm ci` (which fails on package.json / lock drift), never a
 *    hard-coded `npm install` (which can rewrite the lock). Static over the
 *    runner source.
 * B. Profile selection and verdict honesty: default vs `--live` vs `--help` vs an
 *    unknown option, and the verdict each renders, via the runner's pure exports.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
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

// ---- B. Profile selection and verdict honesty ----
//
// Requiring the runner must spawn nothing: the run loop sits behind
// `require.main === module`.
const runner = require('./test-mcp-conformance.cjs');

test('no flag selects the default profile, --live the live profile, and --help / -h help', () => {
  assert.deepEqual(
    [[], ['--live'], ['--help'], ['-h']].map((argv) => runner.parseArgs(argv)),
    [
      { help: false, profile: runner.PROFILE_DEFAULT, error: null },
      { help: false, profile: runner.PROFILE_LIVE, error: null },
      { help: true, profile: runner.PROFILE_DEFAULT, error: null },
      { help: true, profile: runner.PROFILE_DEFAULT, error: null },
    ],
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
});

test('a failing gate renders a profile-named FAILED verdict in both profiles', () => {
  const dflt = reportFor(runner.PROFILE_DEFAULT, { index: 2, status: 1 });
  assert.match(dflt, /MCP-CONFORMANCE DEFAULT PROFILE FAILED — .* exited 1/);
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
});

run();
