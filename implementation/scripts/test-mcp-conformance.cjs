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
 * Gates #4 + #5 ride on `tools/mcp-conformance/npm test` (which itself
 * dispatches via `scripts/test-all.cjs` — exec-safety + re-frame2-pair +
 * live-overflow SKIP + live-subscribe SKIP + story + flag-gates). The
 * re-frame2-pair conformance harness drives the compiled
 * `out/server.js` Node bundle, so this script also runs
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
 * Hermetic live-overflow (which boots shadow-cljs + Playwright Chromium
 * against `skills/re-frame2-pair/tests/fixture/`) is intentionally NOT
 * chained here — that gate lives in CI's `mcp-conformance-re-frame2-pair`
 * job and burns ~2 min of wall-clock on a cold-cache run. Operators who
 * want it run `cd tools/mcp-conformance && npm run test:re-frame2-pair-live-overflow-hermetic`
 * directly.
 */
'use strict';

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
const crossSpawn = require('cross-spawn');
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
const STEPS = [
  // ---- prep ----
  {
    name: 'install tools/re-frame2-pair-mcp deps',
    exe: 'npm',
    args: ['install'],
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
    exe: 'npm',
    args: ['install'],
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

for (const step of STEPS) {
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
