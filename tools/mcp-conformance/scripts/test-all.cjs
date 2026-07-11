#!/usr/bin/env node
/* Sequential, fail-fast `npm test` orchestrator. It forwards the first
 * failing exit code and distinguishes an exit-0 SKIP banner from a pass. */
'use strict';

const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { LIVE_TESTS } = require('./live-test-inventory.cjs');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..');

// Both this runner and the hermetic runner derive the live roster here.
// Without SHADOW_CLJS_NREPL_PORT these rows report SKIP.
const LIVE_ROWS = LIVE_TESTS.map((t) => ({
  name: `re-frame2-pair-mcp ${t.name} (SKIPs without $SHADOW_CLJS_NREPL_PORT)`,
  argv: [`test/${t.basename}`],
}));

// Authoritative Node inventory. Keep cheap `node --test` rows first;
// test-unit.cjs derives that subset from argv[0] rather than re-listing it.
const TESTS = [
  {
    name: 'exec-safety unit tests',
    argv: ['--test', 'test/exec-safety.test.cjs'],
  },
  {
    name: 'runner watchdog connect-hang teardown',
    argv: ['--test', 'test/runner-watchdog.test.cjs'],
  },
  {
    name: 'hermetic async cleanup awaited + graded teardown',
    argv: ['--test', 'test/runner-cleanup.test.cjs'],
  },
  {
    name: 'hermetic teardown grading refuses green on a dirty cleanup',
    argv: ['--test', 'test/hermetic-grading.test.cjs'],
  },
  {
    name: 'hermetic setup-command timeout',
    argv: ['--test', 'test/hermetic-setup-timeout.test.cjs'],
  },
  {
    name: 'hermetic inner-test grading on close, not exit',
    argv: ['--test', 'test/inner-test-close-grading.test.cjs'],
  },
  {
    name: 'live-gate disk and shared-inventory completeness',
    argv: ['--test', 'test/live-list-completeness.test.cjs'],
  },
  {
    name: 'hermetic port-file escape refusal',
    argv: ['--test', 'test/port-file-escape.test.cjs'],
  },
  {
    name: 'hermetic stale port-file wipe fails on a surviving stale file',
    argv: ['--test', 'test/stale-port-file-trust.test.cjs'],
  },
  {
    name: 'dedup-envelope decoder rejects cycles and expands acyclic caches',
    argv: ['--test', 'test/dedup-envelope.test.cjs'],
  },
  {
    name: 'exact-token matching distinguishes overlapping reason names',
    argv: ['--test', 'test/token-match.test.cjs'],
  },
  {
    name: 'overflow marker: closed single-key wrapper + dual-slot agreement',
    argv: ['--test', 'test/overflow-marker.test.cjs'],
  },
  {
    name: 'universal isError and :ok? cross-check after dedup decoding',
    argv: ['--test', 'test/is-error-matches-ok.test.cjs'],
  },
  {
    name: 'callTool coverage ratchet unit tests',
    argv: ['--test', 'test/call-coverage-ratchet.test.cjs'],
  },
  {
    name: 'descriptor classification and open-world partition ratchet',
    argv: ['--test', 'test/classification-ratchet.test.cjs'],
  },
  {
    name: 're-frame2-pair-mcp end-to-end',
    argv: ['test/end-to-end-re-frame2-pair.cjs'],
  },
  // Derived live rows; SKIP by default under `npm test`.
  ...LIVE_ROWS,
  {
    name: 'story-mcp end-to-end',
    argv: ['test/end-to-end-story.cjs'],
  },
  {
    name: 'CLI flag-vocabulary conformance (story-mcp --allow-writes default-OFF + rename rejection)',
    argv: ['test/end-to-end-flag-gates.cjs'],
  },
];

function banner(line) {
  const sep = '─'.repeat(72);
  process.stdout.write('\n' + sep + '\n' + line + '\n' + sep + '\n');
}

// Skip is an exit-0 outcome, so grade the shared banner as well as status.
const SKIP_BANNER = /^SKIP /m;

function main() {
  const results = [];
  let firstFailure = null;

  for (const test of TESTS) {
    banner('▶ ' + test.name + '\n  ' + test.argv.join(' '));
    // Capture stdout for SKIP grading, then replay it. stderr remains live.
    const child = spawnSync(process.execPath, test.argv, {
      cwd: ROOT,
      stdio: ['inherit', 'pipe', 'inherit'],
      env: process.env,
    });
    const stdoutText = child.stdout ? child.stdout.toString('utf8') : '';
    process.stdout.write(stdoutText);
    const status = child.status === null ? 'signal:' + child.signal : child.status;
    const skipped = child.status === 0 && SKIP_BANNER.test(stdoutText);
    results.push({ name: test.name, status, skipped });
    if (child.status !== 0 && firstFailure === null) {
      firstFailure = { test: test.name, status: child.status, signal: child.signal };
      break;
    }
  }

  banner('mcp-conformance summary');
  let skipCount = 0;
  for (const r of results) {
    const tick = r.status !== 0 ? 'FAIL' : r.skipped ? 'SKIP' : 'OK  ';
    if (r.skipped) skipCount++;
    process.stdout.write(`  [${tick}] ${r.name} (exit=${r.status})\n`);
  }

  if (firstFailure) {
    process.stdout.write(
      `\nFAIL: ${firstFailure.test} exited ${firstFailure.status}` +
        (firstFailure.signal ? ' (signal ' + firstFailure.signal + ')' : '') +
        '\n',
    );
    // A signal has no numeric status but must remain a failing outcome.
    process.exit(firstFailure.status === null ? 1 : firstFailure.status);
  }

  if (skipCount > 0) {
    process.stdout.write(
      `\nALL CONFORMANCE TESTS GREEN (${skipCount} SKIPPED — see [SKIP] rows ` +
        'above; the live-re-frame2-pair-* gates require a running server on ' +
        '$SHADOW_CLJS_NREPL_PORT and are not exercised by this run)\n',
    );
  } else {
    process.stdout.write('\nALL CONFORMANCE TESTS GREEN\n');
  }
  process.exit(0);
}

// Importing the inventory is side-effect free.
module.exports = { TESTS, ROOT };

if (require.main === module) {
  main();
}
