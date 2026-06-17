#!/usr/bin/env node

'use strict';

/*
 * Reproducible-install policy gate for `test-mcp-conformance.cjs`
 * (rf2-vtp2er).
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
 * Wired into `package.json` via `test:script-policy`.
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

run();
