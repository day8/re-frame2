#!/usr/bin/env node

'use strict';

/*
 * Source-policy gate (rf2-c3hffe): every `serve-and-run-*.cjs` test
 * orchestrator that spawns a long-lived child server (http-server /
 * shadow-cljs watch / an inner orchestrator) MUST tear that child down on
 * its own exit AND on SIGINT/SIGTERM, via the shared teardown helper in
 * implementation/scripts/lib/local-browser-harness.cjs.
 *
 * WHY THIS EXISTS. On Windows, Node does NOT kill a spawned child when the
 * parent exits, and `spawnSync` does not forward SIGINT/SIGTERM to its
 * child. An orchestrator that spawns a long-lived server and exits (or is
 * Ctrl-C'd / killed by a CI runner) without explicit teardown therefore
 * orphans the server. Orphaned shadow-cljs/Node/http-server processes hold
 * file locks on the worktree (`implementation/`, `tools/`, `out/`), and
 * those stale lock-holders are what deadlock the cross-spec `clojure
 * -M:test` from implementation/core on Windows (rf2-c3hffe; the observed
 * 27h-old stranded xray-feature-gate server). Stopping the leak at source
 * is the durable fix; this gate keeps it stopped.
 *
 * THE HARDENED POSTURE. The shared `createHarnessCleanup()` provides:
 *   - `installSignalHandlers()` — registers process.once('SIGINT'),
 *     process.once('SIGTERM') and process.once('exit', cleanupSync), so a
 *     signal OR a hard exit reaps the tracked children.
 *   - `trackProcess(child)` — registers a spawned child for teardown.
 *   - cross-platform tree-kill (`taskkill /T /F` on Windows; POSIX
 *     process-group kill on Mac/Linux — see the lib).
 * Every spawning orchestrator must (a) import createHarnessCleanup, (b)
 * call installSignalHandlers(), and (c) spawn its long-lived child through
 * `trackProcess(spawnHarnessProcess(...))` so it is reaped on exit/signal.
 *
 * SHORT-LIVED, SELF-EXITING COMPILE STEPS ARE FINE. Most orchestrators run
 * a blocking `spawnSync(process.execPath, [shadow-cljs-runner, 'compile',
 * ...])` for the testbed compile. That child exits on its own (nothing to
 * orphan) and the orchestrator BLOCKS on it, so it needs no teardown — the
 * leak class is the LONG-LIVED server, which must go through the tracked
 * harness. This gate therefore does NOT ban spawnSync outright; it requires
 * the tracked-harness posture for the long-lived server and bans spawnSync
 * specifically for the two prod wrappers that spawn an inner ORCHESTRATOR
 * (a long-lived process) rather than a self-exiting compile.
 *
 * This is a STATIC text gate — it reads the orchestrator sources and
 * asserts on their text; no process is spawned by this suite. The runtime
 * behaviour of the helper itself is covered by
 * _local-browser-harness.test.cjs.
 *
 * Wired into `package.json` via `test:script-policy`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
// Shared stripComments (EXECUTABLE-source-only matching) + framework-free
// test harness (rf2-j552l2).
const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');

const IMPL_SCRIPTS_DIR = __dirname;
const EXAMPLES_SCRIPTS_DIR = path.resolve(
  __dirname,
  '..',
  '..',
  'examples',
  'scripts',
);

const { test, run } = createPolicyTestSuite('orchestrator-teardown-policy');

// The full inventory of `serve-and-run-*.cjs` orchestrators across the two
// scripts dirs. Every one is launched by an `npm run test:*` entry-point
// and spawns a long-lived child (or an inner orchestrator that does). Each
// MUST carry the shared teardown.
//
// Discovery is dynamic, so a NEW serve-and-run-*.cjs is picked up
// automatically (a new leaky orchestrator trips this gate on its first
// commit). The count floor below guards against the file set silently
// shrinking to zero (a path regression that would make the gate vacuously
// green).
function serveAndRunFilesIn(dir) {
  return fs
    .readdirSync(dir)
    .filter((f) => f.startsWith('serve-and-run-') && f.endsWith('.cjs'))
    .filter((f) => !f.endsWith('.test.cjs'))
    .map((f) => path.join(dir, f));
}

function orchestratorFiles() {
  return [
    ...serveAndRunFilesIn(IMPL_SCRIPTS_DIR),
    ...serveAndRunFilesIn(EXAMPLES_SCRIPTS_DIR),
  ];
}

const CREATE_CLEANUP_RE = /\bcreateHarnessCleanup\b/;
const INSTALL_SIGNALS_RE = /\.installSignalHandlers\(\)/;
const TRACK_PROCESS_RE = /\.trackProcess\(/;
// The long-lived server (http-server, shadow-cljs watch, or an inner
// orchestrator) MUST be spawned through the tracked, signal-handled shared
// harness primitive: `trackProcess(spawnHarnessProcess(...))`. Whitespace/
// newlines between the call and its argument are tolerated.
const TRACKED_SPAWN_RE = /trackProcess\(\s*spawnHarnessProcess\(/;
const SPAWNSYNC_RE = /\bspawnSync\s*\(/;

const ORCHESTRATORS = orchestratorFiles();

// Inventory floor: the two scripts dirs hold the known serve-and-run
// orchestrators. If discovery returns far fewer than expected, the gate
// would be vacuously green — fail loud instead.
test('orchestrator inventory is non-trivial (rf2-c3hffe)', () => {
  assert.ok(
    ORCHESTRATORS.length >= 8,
    `expected at least 8 serve-and-run-*.cjs orchestrators across ` +
      `implementation/scripts + examples/scripts, found ${ORCHESTRATORS.length}. ` +
      `Did the scripts dirs move?`,
  );
});

for (const file of ORCHESTRATORS) {
  const base = path.basename(file);
  const code = stripComments(fs.readFileSync(file, 'utf8'));

  test(`${base}: uses the shared createHarnessCleanup teardown helper (rf2-c3hffe)`, () => {
    assert.match(
      code,
      CREATE_CLEANUP_RE,
      `${base} spawns a server but does not import the shared teardown ` +
        `helper. Use createHarnessCleanup() from ` +
        `./lib/local-browser-harness.cjs (or the ../../implementation/scripts ` +
        `copy for examples/scripts) so the spawned child is reaped on ` +
        `exit/SIGINT/SIGTERM. An orphaned server holds worktree file locks ` +
        `and deadlocks the cross-spec JVM suite on Windows (rf2-c3hffe).`,
    );
  });

  test(`${base}: installs exit/SIGINT/SIGTERM teardown handlers (rf2-c3hffe)`, () => {
    assert.match(
      code,
      INSTALL_SIGNALS_RE,
      `${base} must call cleanup.installSignalHandlers() so a SIGINT/` +
        `SIGTERM (Ctrl-C, CI runner kill) or a hard process exit tree-kills ` +
        `the spawned server. Without it the server is orphaned on signal ` +
        `(rf2-c3hffe).`,
    );
  });

  test(`${base}: tracks the spawned child for teardown (rf2-c3hffe)`, () => {
    assert.match(
      code,
      TRACK_PROCESS_RE,
      `${base} must register its spawned child via cleanup.trackProcess(...) ` +
        `so the teardown sweep can tree-kill it. A spawn that is not tracked ` +
        `is not reaped (rf2-c3hffe).`,
    );
  });

  test(`${base}: spawns its long-lived child via the tracked shared harness (rf2-c3hffe)`, () => {
    assert.match(
      code,
      TRACKED_SPAWN_RE,
      `${base} must spawn its long-lived child (http-server / shadow-cljs ` +
        `watch / inner orchestrator) via ` +
        `trackProcess(spawnHarnessProcess(...)) — that is the only spawn ` +
        `posture the teardown sweep reaps cross-platform. A short-lived, ` +
        `self-exiting shadow-cljs compile via spawnSync is fine and is NOT ` +
        `what this checks (rf2-c3hffe).`,
    );
  });
}

// Positive guards: pin the rf2-c3hffe fix for the two prod wrappers so a
// future edit can't quietly regress them back to a blocking, teardown-less
// spawnSync. Unlike the other orchestrators (whose spawnSync is a
// self-exiting compile), these wrappers' only child is the LONG-LIVED inner
// serve-and-run-browser-tests.cjs orchestrator (which holds the real
// http-server) — so a spawnSync here orphans that server on signal, and is
// banned outright.
const PROD_WRAPPERS = [
  'serve-and-run-prod-elision-tests.cjs',
  'serve-and-run-schemas-boundary-prod-tests.cjs',
];
for (const base of PROD_WRAPPERS) {
  test(`${base}: spawns the inner orchestrator async + tracked, never blocking spawnSync (rf2-c3hffe)`, () => {
    const code = stripComments(
      fs.readFileSync(path.join(IMPL_SCRIPTS_DIR, base), 'utf8'),
    );
    assert.match(
      code,
      TRACKED_SPAWN_RE,
      `${base} must launch the inner orchestrator via ` +
        `trackProcess(spawnHarnessProcess(...)) so a SIGINT/SIGTERM to the ` +
        `wrapper tree-kills the inner orchestrator + the http-server it ` +
        `holds (rf2-c3hffe).`,
    );
    assert.doesNotMatch(
      code,
      SPAWNSYNC_RE,
      `${base} must NOT use spawnSync for the inner orchestrator — its only ` +
        `child is a long-lived process and spawnSync does not forward ` +
        `signals to its child on Windows (the orphan class rf2-c3hffe ` +
        `removed).`,
    );
  });
}

// stripComments sanity: it must remove a required-symbol mention that
// appears only in a comment, but keep one in real code. Guards the gate
// against false-positives (a comment satisfying a positive assertion) and
// false-negatives (a comment masking a forbidden spawnSync).
test('stripComments removes comment text but preserves code (rf2-c3hffe)', () => {
  assert.doesNotMatch(
    stripComments('// createHarnessCleanup()\nconst x = 1;'),
    CREATE_CLEANUP_RE,
  );
  assert.doesNotMatch(
    stripComments('/* spawnSync(...) */\nconst y = 2;'),
    SPAWNSYNC_RE,
  );
  assert.match(stripComments('const c = createHarnessCleanup();'), CREATE_CLEANUP_RE);
  assert.match(stripComments('spawnSync(node, args);'), SPAWNSYNC_RE);
  assert.match(
    stripComments('cleanup.trackProcess(spawnHarnessProcess(node, a));'),
    TRACKED_SPAWN_RE,
  );
});

run();
