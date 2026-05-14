#!/usr/bin/env node
/*
 * Sequential orchestrator for the mcp-conformance test suite (rf2-i3ffz
 * F-HYG-3). Spawns each conformance test in sequence with structured
 * per-test exit-code reporting; replaces a five-deep `&&` chain in the
 * `npm test` script.
 *
 * Why an orchestrator: the historical `npm test` was
 *
 *     node --test test/exec-safety.test.cjs
 *     && node test/end-to-end-pair2.cjs
 *     && node test/live-pair2-overflow.cjs
 *     && node test/end-to-end-story.cjs
 *     && node test/end-to-end-causa.cjs
 *
 * compressed into one quoted JSON string. Failure attribution required
 * reading the chain backwards from the exit code; a real failure in
 * `exec-safety.test.cjs` was the only thing the chain caught early,
 * with every downstream test hidden until the upstream link recovered.
 *
 * This orchestrator runs each test in sequence, records its exit
 * status, and prints a structured summary at the end. It still bails
 * on the first failure (so CI fails fast) but the summary makes
 * failure attribution one-glance instead of one-grep.
 *
 * Exit codes:
 *   0  every test passed (some may have SKIPped — they still exit 0)
 *   N  the exit code of the first test that failed (forwarded verbatim
 *      so a downstream failure that itself uses a non-1 exit code —
 *      e.g. the runner's watchdog `process.exit(2)` — surfaces
 *      faithfully to the parent shell).
 */
'use strict';

const path = require('node:path');
const { spawnSync } = require('node:child_process');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..');

// One row per conformance test. Order matters: cheap unit tests first
// so a typo in the exec-safety helpers fails before we spawn an MCP
// server. SKIP-by-default tests (live-pair2-overflow, causa) live near
// the end so a green run reads as "real conformance passed, two
// gracefully skipped".
const TESTS = [
  {
    name: 'exec-safety unit tests',
    argv: ['--test', 'test/exec-safety.test.cjs'],
  },
  {
    name: 'pair2-mcp end-to-end',
    argv: ['test/end-to-end-pair2.cjs'],
  },
  {
    name: 'pair2-mcp live-overflow (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-pair2-overflow.cjs'],
  },
  {
    name: 'pair2-mcp live-subscribe (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-pair2-subscribe.cjs'],
  },
  {
    name: 'story-mcp end-to-end',
    argv: ['test/end-to-end-story.cjs'],
  },
  {
    name: 'causa-mcp end-to-end (SKIP — impl not landed)',
    argv: ['test/end-to-end-causa.cjs'],
  },
];

function banner(line) {
  const sep = '─'.repeat(72);
  process.stdout.write('\n' + sep + '\n' + line + '\n' + sep + '\n');
}

const results = [];
let firstFailure = null;

for (const test of TESTS) {
  banner('▶ ' + test.name + '\n  ' + test.argv.join(' '));
  // `process.execPath` is always absolute; `spawnSync` inherits stdio
  // so the child's stdout/stderr stream through verbatim (matching
  // every historical caller's posture). `shell: false` keeps the
  // accident-gating from the lib/exec-safety.cjs preamble: no
  // shell-interpolation of test names.
  const child = spawnSync(process.execPath, test.argv, {
    cwd: ROOT,
    stdio: 'inherit',
    env: process.env,
  });
  const status = child.status === null ? 'signal:' + child.signal : child.status;
  results.push({ name: test.name, status });
  if (child.status !== 0 && firstFailure === null) {
    firstFailure = { test: test.name, status: child.status, signal: child.signal };
    break;
  }
}

banner('mcp-conformance summary');
for (const r of results) {
  const tick = r.status === 0 ? 'OK  ' : 'FAIL';
  process.stdout.write(`  [${tick}] ${r.name} (exit=${r.status})\n`);
}

if (firstFailure) {
  process.stdout.write(
    `\nFAIL: ${firstFailure.test} exited ${firstFailure.status}` +
      (firstFailure.signal ? ' (signal ' + firstFailure.signal + ')' : '') +
      '\n',
  );
  // Forward the inner exit code verbatim. `null` (signal-terminated)
  // becomes 1 so the parent shell still sees a non-zero exit.
  process.exit(firstFailure.status === null ? 1 : firstFailure.status);
}

process.stdout.write('\nALL CONFORMANCE TESTS GREEN\n');
process.exit(0);
