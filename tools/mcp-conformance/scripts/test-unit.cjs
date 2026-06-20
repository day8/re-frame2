#!/usr/bin/env node
/*
 * PR-CI Node-regression gate (rf2-md05gp).
 *
 * Runs the pure-Node UNIT cluster of the conformance inventory — every
 * row in `scripts/test-all.cjs`'s authoritative `TESTS` array whose
 * `argv[0] === '--test'` (i.e. invoked via `node --test`). These are the
 * harness's own regression nets: the runner watchdog teardown, the
 * awaited hermetic cleanup, the bounded setup-command timeout, the
 * symlink-safe port-file read, the dedup-envelope decoder, and the
 * call-coverage / classification ratchets. They boot no MCP server, need
 * no nREPL / clojure / Chromium, and finish in a couple of seconds.
 *
 * ## Why this script exists
 *
 * `test-all.cjs` is the single source of truth for the conformance
 * inventory, but it spawns EVERY test — including the end-to-end (server
 * bundle), live (nREPL), story (JVM `clojure`), and hermetic
 * (shadow-cljs + Chromium) gates. Those are run by dedicated CI jobs
 * (`mcp-conformance-{re-frame2-pair,story}`) with their own toolchains
 * and caches. Pre-rf2-md05gp the PR CI for those jobs ran ONLY
 * `test:exec-safety` (+ the e2e / live / story steps); the other seven
 * `node --test` unit gates were in the local inventory but NEVER ran in
 * CI, so a regression they guard could ship green.
 *
 * This runner DERIVES its set from `test-all.cjs` rather than
 * re-listing, so it cannot drift: add a new `node --test` row to the
 * authoritative array and PR CI picks it up automatically. It runs the
 * whole cluster in ONE `node --test` invocation (Node's test runner
 * aggregates results across files and exits non-zero if any file fails),
 * which is faster than spawning each file separately and gives one
 * combined summary.
 *
 * Exit codes: 0 = every unit gate green; non-zero = at least one failed
 * (forwarded from the `node --test` child).
 */
'use strict';

const path = require('node:path');
const { spawnSync } = require('node:child_process');

const { TESTS, ROOT } = require('./test-all.cjs');

// The unit cluster is exactly the rows invoked through `node --test`.
// The end-to-end / live / story / flag-gates rows are plain
// `node <script>` (no `--test`) and are excluded — they belong to the
// dedicated server jobs, not this Node-only unit gate.
const UNIT_FILES = TESTS.filter((t) => t.argv[0] === '--test').map(
  (t) => t.argv[t.argv.length - 1],
);

if (UNIT_FILES.length === 0) {
  process.stderr.write(
    'test-unit: no `node --test` rows found in the authoritative inventory — ' +
      'refusing to report green with an empty gate set.\n',
  );
  process.exit(1);
}

const sep = '─'.repeat(72);
process.stdout.write(
  '\n' +
    sep +
    '\n▶ mcp-conformance Node unit cluster (rf2-md05gp)\n  node --test ' +
    UNIT_FILES.join(' ') +
    '\n' +
    sep +
    '\n',
);

// One `node --test` invocation across every unit file: the runner
// aggregates and exits non-zero on any failure. `cwd: ROOT` so the
// relative `test/...` paths resolve against the artefact root regardless
// of the caller's cwd. `shell: false` (spawnSync default) — no
// shell-interpolation of file paths.
const child = spawnSync(process.execPath, ['--test', ...UNIT_FILES], {
  cwd: ROOT,
  stdio: 'inherit',
  env: process.env,
});

if (child.status === null) {
  process.stderr.write(
    `\ntest-unit: node --test terminated by signal ${child.signal}\n`,
  );
  process.exit(1);
}

if (child.status === 0) {
  process.stdout.write('\nMCP-CONFORMANCE NODE UNIT CLUSTER GREEN\n');
}
process.exit(child.status);
