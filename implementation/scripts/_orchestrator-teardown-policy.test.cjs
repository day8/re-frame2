#!/usr/bin/env node

'use strict';

/*
 * Source policy: every serve-and-run-*.cjs orchestrator installs the shared
 * harness's exit/SIGINT/SIGTERM teardown and spawns its long-lived child through
 * it (trackProcess(spawnHarnessProcess(...)) or startLocalHttpServer(...)), so the
 * child cannot be orphaned holding ports and worktree file locks. A short-lived,
 * self-exiting spawnSync compile needs no teardown. The helper's runtime behaviour
 * is covered by _local-browser-harness.test.cjs. Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
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

// Discovered dynamically, so a new orchestrator is checked on its first commit.
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

const INSTALL_SIGNALS_RE = /\.installSignalHandlers\(\)/;
const TRACKED_SPAWN_RE = /trackProcess\(\s*spawnHarnessProcess\(/;
const START_LOCAL_HTTP_SERVER_RE = /\bstartLocalHttpServer\s*\(/;

const ORCHESTRATORS = orchestratorFiles();

// A floor, so a moved scripts dir cannot leave the gate vacuously green.
test('orchestrator inventory is non-trivial', () => {
  assert.ok(ORCHESTRATORS.length >= 6, `expected at least 6 orchestrators, found ${ORCHESTRATORS.length}`);
});

for (const file of ORCHESTRATORS) {
  const base = path.basename(file);
  const code = stripComments(fs.readFileSync(file, 'utf8'));

  test(`${base}: installs teardown handlers and spawns its long-lived child through the tracked harness`, () => {
    assert.match(code, INSTALL_SIGNALS_RE, `${base} must call cleanup.installSignalHandlers()`);
    assert.ok(
      TRACKED_SPAWN_RE.test(code) || START_LOCAL_HTTP_SERVER_RE.test(code),
      `${base} must spawn its long-lived child via trackProcess(spawnHarnessProcess(...)) ` +
        'or startLocalHttpServer(...)',
    );
  });
}

run();
