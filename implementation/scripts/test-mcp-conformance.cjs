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
const { spawnSync } = require('node:child_process');

const HERE = __dirname;
const IMPL_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const TOOLS = path.join(REPO_ROOT, 'tools');
const STORY_MCP = path.join(TOOLS, 'story-mcp');
const PAIR_MCP = path.join(TOOLS, 're-frame2-pair-mcp');
const CONFORMANCE = path.join(TOOLS, 'mcp-conformance');
const WIRE_VOCAB = path.join(CONFORMANCE, 'wire-vocab');

// `clojure` and `npm`/`npx` are resolved via PATH on Windows as `.cmd`
// shims and on POSIX as plain binaries. `shell: true` lets the OS shell
// do that resolution for us so the same `command` string works
// cross-platform without per-platform branching.
const STEPS = [
  // ---- prep ----
  {
    name: 'install tools/re-frame2-pair-mcp deps',
    command: 'npm install',
    cwd: PAIR_MCP,
    prep: true,
  },
  {
    name: 'compile re-frame2-pair-mcp server bundle (shadow-cljs :server)',
    command: 'npx shadow-cljs compile server',
    cwd: PAIR_MCP,
    prep: true,
  },
  {
    name: 'install tools/mcp-conformance deps',
    command: 'npm install',
    cwd: CONFORMANCE,
    prep: true,
  },

  // ---- the six gates ----
  {
    name: '[1/6] JVM tools/story-mcp (clojure -M:test)',
    command: 'clojure -M:test',
    cwd: STORY_MCP,
  },
  {
    name: '[2/6] Node tools/story-mcp stdio roundtrip (rf2-h8z5l)',
    command: 'node test/stdio-roundtrip.js',
    cwd: STORY_MCP,
  },
  {
    name: '[3/6] Node tools/re-frame2-pair-mcp (shadow-cljs :server-test)',
    command: 'npm test',
    cwd: PAIR_MCP,
  },
  {
    name: '[4+5/6] MCP conformance tools/re-frame2-pair-mcp + tools/story-mcp (rf2-cum40)',
    command: 'node scripts/test-all.cjs',
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
    command: 'clojure -M:test',
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
  banner('▶ ' + step.name + '\n  cwd: ' + step.cwd + '\n  cmd: ' + step.command);
  const child = spawnSync(step.command, {
    cwd: step.cwd,
    stdio: 'inherit',
    shell: true,
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
