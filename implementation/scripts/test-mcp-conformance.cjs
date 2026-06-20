#!/usr/bin/env node
/*
 * Single MCP-conformance entry-point for operator pairs (rf2-gt4pf).
 *
 * Chains the six MCP-conformance gates that PR CI runs as separate jobs
 * (`.github/workflows/test.yml`):
 *
 *   1. JVM tools/story-mcp         (`clojure -M:test`)
 *   2. Node tools/story-mcp        stdio roundtrip (rf2-h8z5l)
 *   3. Node tools/re-frame2-pair-mcp  shadow-cljs :server-test
 *   4. MCP conformance tools/re-frame2-pair-mcp  (SDK Client driver, rf2-cum40)
 *   5. MCP conformance tools/story-mcp           (SDK Client driver, rf2-cum40)
 *   6. MCP conformance wire-vocab  (rf2-j2z7o + rf2-6m8tq + rf2-zvv65)
 *
 * Gates #4 + #5 ride on `tools/mcp-conformance/npm test`, which dispatches
 * via `tools/mcp-conformance/scripts/test-all.cjs`. That orchestrator's
 * `TESTS` array IS the authoritative conformance inventory (exec-safety +
 * runner/hermetic unit gates + re-frame2-pair end-to-end + the SKIP-gated
 * live-* probes + story end-to-end + flag-gates) — this wrapper does not
 * re-enumerate it. The re-frame2-pair conformance harness drives the
 * compiled `out/server.js` Node bundle, so this script also runs
 * `shadow-cljs compile server` from `tools/re-frame2-pair-mcp/`
 * before invoking the conformance suite — matching what
 * `mcp-conformance-re-frame2-pair` does in CI.
 *
 * Fail-fast: the first non-zero exit code halts the run and the
 * orchestrator forwards it verbatim, so the parent shell sees exactly
 * which gate failed. The summary at the end attributes pass/fail per
 * gate one-glance, matching the shape of
 * `tools/mcp-conformance/scripts/test-all.cjs`.
 *
 * Out of scope per Mike's minimum-scope direction (rf2-gt4pf):
 *   - Formal runner ns (this is a Node operator-side ergonomic, not a
 *     CI gate — CI keeps the six split jobs for differential surface
 *     attribution)
 *   - Rich output formatting / unified report
 *   - Incremental `--changed-only` mode
 *
 * The hermetic live conformance gate (which boots shadow-cljs + Playwright
 * Chromium against `skills/re-frame2-pair/tests/fixture/` and runs the
 * SKIP-gated live probes against it — its `INNER_TESTS` array in
 * `tools/mcp-conformance/scripts/run-re-frame2-pair-live-hermetic-suite.cjs`
 * is the authoritative list) is intentionally NOT chained here. That gate
 * lives in CI's `mcp-conformance-re-frame2-pair` job and burns ~2 min of
 * wall-clock on a cold-cache run. Operators who want it run
 * `cd tools/mcp-conformance && npm run test:re-frame2-pair-live-hermetic-suite`
 * directly.
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const HERE = __dirname;
const IMPL_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const TOOLS = path.join(REPO_ROOT, 'tools');
const STORY_MCP = path.join(TOOLS, 'story-mcp');
const PAIR_MCP = path.join(TOOLS, 're-frame2-pair-mcp');
const CONFORMANCE = path.join(TOOLS, 'mcp-conformance');
const WIRE_VOCAB = path.join(CONFORMANCE, 'wire-vocab');

// ---------------------------------------------------------------------
// Exec-safety: no `shell: true`, no bare-name spawns (rf2-1irs7).
//
// The original form spawned every step with
// `spawnSync(bareCommandString, { shell: true, cwd })`. On Windows that
// is the rf2-33vvc command-hijack accident class: `shell: true` + a
// bare exe name (`npm`/`npx`/`clojure`) + a repo-controlled `cwd`
// resolves against the cwd ahead of PATH, so a checkout that ever
// carried a `npm.cmd` / `npx.cmd` / `clojure.cmd` in one of these dirs
// (a fixture dir, anywhere in PATHEXT order) would silently execute it.
//
// The mcp-conformance slice already SOLVED this for its inner spawn
// sites (`tools/mcp-conformance/test/end-to-end-story.cjs`,
// `scripts/run-live-...-hermetic.cjs`): resolve each tool name to a
// single trusted absolute path OUTSIDE the workspace via
// `resolveTrustedExe`, then spawn with an args ARRAY and NO shell. We
// reuse that exact primitive here rather than hand-roll a second one.
//
// `cross-spawn` (the slice's own spawn helper) is the cross-platform
// piece: given the trusted absolute path `resolveTrustedExe` returns —
// on Windows that is typically the extensionless `npm`/`npx` shim under
// the Node install dir, NOT the `.cmd` — Node's built-in `spawnSync`
// with `shell: false` fails (ENOENT on the shim; EINVAL on a `.cmd`
// since the CVE-2024-27980 fix). `cross-spawn` reads the shebang /
// dispatches the `.cmd` correctly without re-introducing the shell, so
// `resolveTrustedExe` + `cross-spawn` is the only combination that is
// both hardened and cross-platform (Windows / macOS / Linux).
// rf2-ocfiq — `cross-spawn` is an `implementation/` devDependency added
// by rf2-1irs7. It is required at module-load (before any STEP runs), so
// a STALE `implementation/node_modules` (one that predates the 1irs7
// install — e.g. an operator who pulled the change but didn't re-run
// `npm install`) would otherwise crash here with a raw loader stack
// (`Error: Cannot find module 'cross-spawn'`) that gives NO hint the fix
// is a one-time `npm install`. The script's own prep STEPS install deps
// for the TOOLS packages, not for `implementation/` itself, so they
// can't cover this. Fail LOUD with an actionable hint instead.
let crossSpawn;
try {
  crossSpawn = require('cross-spawn');
} catch (err) {
  if (err && err.code === 'MODULE_NOT_FOUND') {
    process.stderr.write(
      "\ntest:mcp-conformance: 'cross-spawn' is not installed.\n" +
        '  This entry-point requires the implementation/ devDependencies.\n' +
        '  Fix: run `npm install` in implementation/ first ' +
        '(rf2-1irs7 added cross-spawn as a devDependency).\n\n',
    );
    process.exit(1);
  }
  throw err;
}
const { resolveTrustedExe } = require(
  path.join(CONFORMANCE, 'lib', 'exec-safety.cjs'),
);

// Cache one resolution per tool name; the lookup walks PATH + PATHEXT
// and is identical for every step that uses the same tool.
const TRUSTED_EXE_CACHE = new Map();
function trustedExe(name) {
  if (TRUSTED_EXE_CACHE.has(name)) return TRUSTED_EXE_CACHE.get(name);
  const resolved = resolveTrustedExe(name, { workspaceRoot: REPO_ROOT });
  TRUSTED_EXE_CACHE.set(name, resolved);
  return resolved;
}

// `node` steps use `process.execPath` — always an absolute path,
// always the currently-running Node, always outside the workspace by
// construction — so they skip the PATH walk entirely (the same posture
// the slice's `scripts/test-all.cjs` uses for its own node sub-tests).
// Reproducible-install posture (rf2-vtp2er). A bare `npm install` mutates
// dependency state on every run — it can rewrite node_modules and (for a
// package without a committed lockfile) resolve semver ranges against
// whatever the registry happens to publish at run time. That makes a local
// `npm run test:mcp-conformance` not a pure verification command and leaves
// dirty-worktree noise for workers. We pin each tool package to the most
// reproducible install its on-disk state allows:
//
//   - A package WITH a committed package-lock.json (tools/mcp-conformance)
//     installs via `npm ci`: a clean, lockfile-exact install that FAILS if
//     package.json and the lock have drifted, rather than silently
//     rewriting the lock. (`npm ci` requires the lock, so it can only be
//     used where one is committed.)
//   - A package WITHOUT a committed lockfile (tools/re-frame2-pair-mcp is a
//     PUBLISHED npm package that deliberately .gitignores its lock — see
//     tools/re-frame2-pair-mcp/.gitignore) falls back to `npm install`, but
//     ONLY when its node_modules is absent. A present node_modules is
//     treated as already-bootstrapped and the install is SKIPPED, so a
//     repeated verification run does not re-mutate dependency state.
//     Whether this package SHOULD carry a committed lockfile is a separate
//     architecture decision flagged to the operator (rf2-vtp2er follow-up);
//     this runner does not decide it.
//
// `resolveInstallStep` turns the declarative `install` marker into the
// concrete exe/args (or a skip) at run time, against the live on-disk
// lockfile / node_modules state.
function hasCommittedLockfile(pkgDir) {
  return fs.existsSync(path.join(pkgDir, 'package-lock.json'));
}

function nodeModulesPresent(pkgDir) {
  return fs.existsSync(path.join(pkgDir, 'node_modules'));
}

function resolveInstallStep(step) {
  const pkgDir = step.cwd;
  if (hasCommittedLockfile(pkgDir)) {
    // Lockfile-exact, fails on drift, never rewrites the lock.
    return { ...step, exe: 'npm', args: ['ci'], skip: false };
  }
  if (nodeModulesPresent(pkgDir)) {
    // Already bootstrapped + no committed lock to install against —
    // skip rather than re-mutate. The compile/test steps that follow
    // surface any genuinely-missing dep with an actionable error.
    return { ...step, skip: true };
  }
  // No lock and no node_modules — first-run bootstrap. `npm install` is
  // the only option here (npm ci requires a lock); it is reproducible
  // enough for a first bootstrap and won't run again once node_modules
  // exists.
  return { ...step, exe: 'npm', args: ['install'], skip: false };
}

const STEPS = [
  // ---- prep ----
  {
    name: 'install tools/re-frame2-pair-mcp deps',
    install: true,
    cwd: PAIR_MCP,
    prep: true,
  },
  {
    name: 'compile re-frame2-pair-mcp server bundle (shadow-cljs :server)',
    exe: 'npx',
    args: ['shadow-cljs', 'compile', 'server'],
    cwd: PAIR_MCP,
    prep: true,
  },
  {
    name: 'install tools/mcp-conformance deps',
    install: true,
    cwd: CONFORMANCE,
    prep: true,
  },

  // ---- the six gates ----
  {
    name: '[1/6] JVM tools/story-mcp (clojure -M:test)',
    exe: 'clojure',
    args: ['-M:test'],
    cwd: STORY_MCP,
  },
  {
    name: '[2/6] Node tools/story-mcp stdio roundtrip (rf2-h8z5l)',
    node: true,
    args: ['test/stdio-roundtrip.js'],
    cwd: STORY_MCP,
  },
  {
    name: '[3/6] Node tools/re-frame2-pair-mcp (shadow-cljs :server-test)',
    exe: 'npm',
    args: ['test'],
    cwd: PAIR_MCP,
  },
  {
    name: '[4+5/6] MCP conformance tools/re-frame2-pair-mcp + tools/story-mcp (rf2-cum40)',
    node: true,
    args: ['scripts/test-all.cjs'],
    cwd: CONFORMANCE,
    // tools/mcp-conformance/npm test == node scripts/test-all.cjs;
    // it covers BOTH gates [4] (re-frame2-pair-mcp end-to-end + live-*
    // SKIPs) and [5] (story-mcp end-to-end + flag-gates) in one
    // orchestrator pass, alongside the exec-safety unit tests. The
    // upstream orchestrator already prints its own per-test summary
    // (see `tools/mcp-conformance/scripts/test-all.cjs`), so we don't
    // artificially split its output.
  },
  {
    name: '[6/6] MCP conformance wire-vocab (rf2-j2z7o + rf2-6m8tq + rf2-zvv65)',
    exe: 'clojure',
    args: ['-M:test'],
    cwd: WIRE_VOCAB,
  },
];

function banner(line) {
  const sep = '─'.repeat(72);
  process.stdout.write('\n' + sep + '\n' + line + '\n' + sep + '\n');
}

const results = [];
let firstFailure = null;

for (const rawStep of STEPS) {
  // Install steps resolve their concrete command (npm ci / npm install /
  // skip) from the live on-disk lockfile + node_modules state (rf2-vtp2er).
  const step = rawStep.install ? resolveInstallStep(rawStep) : rawStep;

  if (step.skip) {
    banner(
      '▷ ' + step.name +
        '\n  cwd: ' + step.cwd +
        '\n  SKIPPED: node_modules already present and no committed ' +
        'lockfile to install against (reproducible-verify; rf2-vtp2er).',
    );
    continue;
  }

  // Resolve the executable up-front: `node` steps use the absolute
  // `process.execPath`; native-tool steps resolve their bare name to a
  // trusted absolute path outside the workspace (rf2-1irs7 / rf2-33vvc).
  const exe = step.node ? process.execPath : trustedExe(step.exe);
  banner(
    '▶ ' + step.name +
      '\n  cwd: ' + step.cwd +
      '\n  exe: ' + exe +
      '\n  args: ' + step.args.join(' '),
  );
  // `cross-spawn` + args ARRAY + NO `shell`: the resolved absolute path
  // is the only thing the OS interprets, so a workspace-local
  // `npm.cmd` / `npx.cmd` / `clojure.cmd` can no longer hijack the
  // invocation (rf2-1irs7). cross-spawn handles the Windows `.cmd` /
  // extensionless-shim dispatch that built-in `spawnSync` can't under
  // `shell: false`.
  const child = crossSpawn.sync(exe, step.args, {
    cwd: step.cwd,
    stdio: 'inherit',
    env: process.env,
  });
  const status = child.status === null ? 'signal:' + child.signal : child.status;
  // Don't include prep steps in the gate-summary (they're not gates, they're prerequisites).
  if (!step.prep) {
    results.push({ name: step.name, status });
  }
  if (child.status !== 0 && firstFailure === null) {
    firstFailure = { step: step.name, status: child.status, signal: child.signal };
    break;
  }
}

banner('test:mcp-conformance summary');
for (const r of results) {
  const tick = r.status === 0 ? 'OK  ' : 'FAIL';
  process.stdout.write(`  [${tick}] ${r.name} (exit=${r.status})\n`);
}

if (firstFailure) {
  process.stdout.write(
    `\nFAIL: ${firstFailure.step} exited ${firstFailure.status}` +
      (firstFailure.signal ? ' (signal ' + firstFailure.signal + ')' : '') +
      '\n',
  );
  process.exit(firstFailure.status === null ? 1 : firstFailure.status);
}

process.stdout.write('\nALL MCP-CONFORMANCE GATES GREEN\n');
process.exit(0);
