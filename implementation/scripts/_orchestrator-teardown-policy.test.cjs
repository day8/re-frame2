#!/usr/bin/env node

'use strict';

/*
 * Source-policy gate: every `serve-and-run-*.cjs` test
 * orchestrator that spawns a long-lived child server (http-server /
 * shadow-cljs watch / an inner orchestrator) MUST tear that child down on
 * its own exit AND on SIGINT/SIGTERM, via the shared teardown helper in
 * implementation/scripts/lib/local-browser-harness.cjs.
 *
 * On Windows, Node does not kill a spawned child when the
 * parent exits, and `spawnSync` does not forward SIGINT/SIGTERM to its
 * child. An orchestrator that spawns a long-lived server and exits (or is
 * interrupted through a handled signal) without explicit teardown can
 * orphans the server. Orphaned shadow-cljs/Node/http-server processes hold
 * file locks on the worktree (`implementation/`, `tools/`, `out/`), and
 * stale lock-holders can block later test and cleanup work. This gate keeps
 * teardown ownership explicit at the spawn site.
 *
 * The shared `createHarnessCleanup()` provides:
 *   - `installSignalHandlers()` — registers process.once('SIGINT'),
 *     process.once('SIGTERM') and process.once('exit', cleanupSync), so a
 *     handled signal or synchronous Node exit reaps the tracked children.
 *   - `trackProcess(child)` — registers a spawned child for teardown.
 *   - cross-platform tree-kill (`taskkill /T /F` on Windows; POSIX
 *     process-group kill on Mac/Linux — see the lib).
 * Every spawning orchestrator must (a) import createHarnessCleanup, (b)
 * call installSignalHandlers(), and (c) spawn its long-lived child through
 * `trackProcess(spawnHarnessProcess(...))` — OR, for its http-server
 * specifically, through the shared `startLocalHttpServer(...)` owner, which
 * performs that tracked spawn internally against the
 * cleanup handle you pass it — so the child is reaped on exit/signal.
 *
 * SHORT-LIVED, SELF-EXITING COMPILE STEPS ARE FINE. Most orchestrators run
 * a blocking `spawnSync(process.execPath, [shadow-cljs-runner, 'compile',
 * ...])` for the testbed compile. That child exits on its own (nothing to
 * orphan) and the orchestrator BLOCKS on it, so it needs no teardown — the
 * leak class is the LONG-LIVED server, which must go through the tracked
 * harness. This gate therefore does not ban spawnSync; it requires the
 * tracked-harness posture for long-lived children.
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
// Shared executable-source filtering and framework-free test harness.
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
// startLocalHttpServer is also a tracked-long-lived-spawn posture: it calls
// `cleanup.trackProcess(spawnHarnessProcess(...))` internally against the
// cleanup handle the caller passes (functionally covered in
// _local-browser-harness.test.cjs), so a caller that delegates to it still
// reaps its server on exit/signal without an inline trackProcess.
const START_LOCAL_HTTP_SERVER_RE = /\bstartLocalHttpServer\s*\(/;
const SPAWNSYNC_RE = /\bspawnSync\s*\(/;

const ORCHESTRATORS = orchestratorFiles();

// Inventory floor: the two scripts dirs hold the known serve-and-run
// orchestrators. If discovery returns far fewer than expected, the gate
// would be vacuously green — fail loud instead.
test('orchestrator inventory is non-trivial', () => {
  assert.ok(
    ORCHESTRATORS.length >= 6,
    `expected at least 6 serve-and-run-*.cjs orchestrators across ` +
      `implementation/scripts + examples/scripts, found ${ORCHESTRATORS.length}. ` +
      `Did the scripts dirs move?`,
  );
});

for (const file of ORCHESTRATORS) {
  const base = path.basename(file);
  const code = stripComments(fs.readFileSync(file, 'utf8'));

  test(`${base}: uses the shared createHarnessCleanup teardown helper`, () => {
    assert.match(
      code,
      CREATE_CLEANUP_RE,
      `${base} spawns a server but does not import the shared teardown ` +
        `helper. Use createHarnessCleanup() from ` +
        `./lib/local-browser-harness.cjs (or the ../../implementation/scripts ` +
        `copy for examples/scripts) so the spawned child is reaped on ` +
        `exit/SIGINT/SIGTERM. An orphaned server can retain ports and worktree ` +
        `file locks.`,
    );
  });

  test(`${base}: installs exit/SIGINT/SIGTERM teardown handlers`, () => {
    assert.match(
      code,
      INSTALL_SIGNALS_RE,
      `${base} must call cleanup.installSignalHandlers() so a SIGINT/` +
        `SIGTERM (Ctrl-C, CI runner kill) or a hard process exit tree-kills ` +
        `the spawned server.`,
    );
  });

  test(`${base}: tracks its long-lived child for teardown`, () => {
    assert.ok(
      TRACK_PROCESS_RE.test(code) || START_LOCAL_HTTP_SERVER_RE.test(code),
      `${base} must register its long-lived child for teardown — either via ` +
        `cleanup.trackProcess(...) directly, or by starting its http-server ` +
        `through the shared startLocalHttpServer(...) owner, which tracks the ` +
        `server in the cleanup handle you pass it. An untracked spawn is not ` +
        `reaped.`,
    );
  });

  test(`${base}: spawns its long-lived child via the tracked shared harness`, () => {
    assert.ok(
      TRACKED_SPAWN_RE.test(code) || START_LOCAL_HTTP_SERVER_RE.test(code),
      `${base} must spawn its long-lived child (http-server / shadow-cljs ` +
        `watch / inner orchestrator) via trackProcess(spawnHarnessProcess(...)) ` +
        `— or, for the http-server specifically, via the shared ` +
        `startLocalHttpServer(...) owner, which performs that tracked spawn ` +
        `internally. That is the only spawn posture the teardown ` +
        `sweep reaps cross-platform. A short-lived, self-exiting shadow-cljs ` +
        `compile via spawnSync is fine and is not what this checks.`,
    );
  });
}

// stripComments sanity: it must remove a required-symbol mention that
// appears only in a comment, but keep one in real code. Guards the gate
// against false-positives (a comment satisfying a positive assertion) and
// false-negatives (a comment masking a forbidden spawnSync).
test('stripComments removes comment text but preserves code', () => {
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
