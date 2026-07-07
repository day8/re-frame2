#!/usr/bin/env node
/*
 * Sequential orchestrator for the mcp-conformance test suite. Spawns each
 * conformance test in sequence with structured per-test exit-code
 * reporting; it is the implementation behind the `npm test` script.
 *
 * Why an orchestrator: it runs each test in sequence, records its exit
 * status, and prints a structured summary at the end. It bails on the
 * first failure (so CI fails fast), and the summary makes failure
 * attribution one-glance instead of one-grep — each test's pass/fail and
 * exit code is listed by name rather than buried in a chained exit code.
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
// server. SKIP-by-default tests (live-re-frame2-pair-overflow) live near
// the end so a green run reads as "real conformance passed, one
// gracefully skipped".
//
// This array is the SINGLE SOURCE OF TRUTH for the conformance
// inventory. It is exported (see the bottom of this file) so derived
// runners can select a subset without re-listing — notably
// `scripts/test-unit.cjs`, which runs the pure-Node unit cluster
// (every row whose `argv[0] === '--test'`) in one `node --test`
// invocation for the PR CI Node-regression gate. Because
// that runner DERIVES its set from this array, a new `--test` row added
// here is automatically picked up by PR CI — no second list to keep in
// sync.
const TESTS = [
  {
    name: 'exec-safety unit tests',
    argv: ['--test', 'test/exec-safety.test.cjs'],
  },
  {
    name: 'runner watchdog connect-hang teardown (rf2-2js41 finding 2 + rf2-wqi4n4 finding 1)',
    argv: ['--test', 'test/runner-watchdog.test.cjs'],
  },
  {
    name: 'hermetic async cleanup awaited teardown (rf2-7ckmwx finding 1)',
    argv: ['--test', 'test/runner-cleanup.test.cjs'],
  },
  {
    name: 'hermetic setup-command timeout (rf2-wqi4n4 finding 2)',
    argv: ['--test', 'test/hermetic-setup-timeout.test.cjs'],
  },
  {
    name: 'hermetic INNER_TESTS grading on close, not exit (rf2-6girz0)',
    argv: ['--test', 'test/inner-test-close-grading.test.cjs'],
  },
  {
    name: 'hermetic port-file escape refusal (rf2-khav7l)',
    argv: ['--test', 'test/port-file-escape.test.cjs'],
  },
  {
    name: 'hermetic stale port-file wipe fails loud on a surviving stale file (rf2-6i2yi4)',
    argv: ['--test', 'test/stale-port-file-trust.test.cjs'],
  },
  {
    name: 'dedup-envelope decoder — cyclic-cache throws + acyclic happy path (rf2-87h71e)',
    argv: ['--test', 'test/dedup-envelope.test.cjs'],
  },
  {
    name: 'exact-token match — anchors :invalid-cofx vs :invalid-cofx-time-ms (rf2-6i2yi4)',
    argv: ['--test', 'test/token-match.test.cjs'],
  },
  {
    name: 'universal isError <-> :ok? cross-check reads through the dedup decoder (rf2-6i2yi4)',
    argv: ['--test', 'test/is-error-matches-ok.test.cjs'],
  },
  {
    name: 'callTool coverage ratchet unit tests (rf2-ke5n56)',
    argv: ['--test', 'test/call-coverage-ratchet.test.cjs'],
  },
  {
    name: 'classification ratchet unit tests — read/destructive + open-world partition (rf2-yi451 / rf2-ppk6hy)',
    argv: ['--test', 'test/classification-ratchet.test.cjs'],
  },
  {
    name: 're-frame2-pair-mcp end-to-end',
    argv: ['test/end-to-end-re-frame2-pair.cjs'],
  },
  {
    name: 're-frame2-pair-mcp live-overflow (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-re-frame2-pair-overflow.cjs'],
  },
  {
    name: 're-frame2-pair-mcp live-subscribe (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-re-frame2-pair-subscribe.cjs'],
  },
  {
    name: 're-frame2-pair-mcp live-redaction (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-re-frame2-pair-redaction.cjs'],
  },
  {
    name: 're-frame2-pair-mcp live-iserror — :ok? false ⇒ isError read-dom/read-ui (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-re-frame2-pair-iserror.cjs'],
  },
  {
    name: 're-frame2-pair-mcp live-cofx — EP-0017 reproducible dispatch + cofx tooling (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-re-frame2-pair-cofx.cjs'],
  },
  {
    name: 're-frame2-pair-mcp live-event-meta — EP-0018 unified event metadata (SKIPs without $SHADOW_CLJS_NREPL_PORT)',
    argv: ['test/live-re-frame2-pair-event-meta.cjs'],
  },
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

// The shared pre-flight skip helper (`_runner.cjs` `runWithWatchdog.skip`)
// prints a `SKIP <reason>` line and exits 0 BEFORE running any real
// assertions. That exit-0 is indistinguishable from a real pass by
// status code alone — the six live-re-frame2-pair-* rows all SKIP this
// way when `$SHADOW_CLJS_NREPL_PORT` is unset, which is the normal state
// of a local `npm test` run. Detect the banner in the child's captured
// stdout so the summary can tell "skipped" apart from "actually ran and
// passed" instead of collapsing both into a bare exit-0 OK.
const SKIP_BANNER = /^SKIP /m;

function main() {
  const results = [];
  let firstFailure = null;

  for (const test of TESTS) {
    banner('▶ ' + test.name + '\n  ' + test.argv.join(' '));
    // `process.execPath` is always absolute. stdout is piped (not
    // inherited) so we can inspect it for the SKIP banner; it is written
    // to our own stdout immediately after the child exits, so nothing is
    // lost — only the live-interleaving with stderr is (stderr stays
    // `inherit`, unaffected). `shell: false` keeps the accident-gating
    // from the lib/exec-safety.cjs preamble: no shell-interpolation of
    // test names.
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
    // Forward the inner exit code verbatim. `null` (signal-terminated)
    // becomes 1 so the parent shell still sees a non-zero exit.
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

// Export the authoritative inventory so derived runners (e.g.
// `scripts/test-unit.cjs`) can select a subset without re-listing.
// `ROOT` rides along so a derived runner resolves test paths against the
// same artefact root. The orchestrator only auto-runs when invoked
// directly (`node scripts/test-all.cjs` / `npm test`), not when required
// as a module — so importing this file has no side effects.
module.exports = { TESTS, ROOT };

if (require.main === module) {
  main();
}
