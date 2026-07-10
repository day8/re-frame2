#!/usr/bin/env node

'use strict';

/*
 * Runtime integration smoke for orchestrator teardown.
 *
 * The static gate (_orchestrator-teardown-policy.test.cjs) proves every
 * serve-and-run-*.cjs orchestrator is WIRED to the shared teardown helper.
 * This test proves the wiring actually reaps a long-lived grandchild when
 * the orchestrator is terminated.
 *
 * Shape:
 *   parent (this test)
 *     └─ orchestrator child  — a tiny script that uses the REAL shared
 *        createHarnessCleanup() to spawn + trackProcess() a long-lived
 *        grandchild "server", prints the grandchild PID on stdout, then
 *        idles forever.
 *          └─ grandchild "server" — `setInterval` forever (stands in for
 *             http-server / shadow-cljs).
 *
 * We let the orchestrator come up, capture the grandchild PID, then
 * terminate the orchestrator and assert the grandchild is gone. That
 * exercises the ownership invariant: a tracked child must not outlive its
 * orchestrator.
 *
 * The first case verifies the observable process-tree result on every
 * platform without depending on which termination hook Node uses. POSIX also
 * gets a dedicated SIGINT case for the installed JavaScript signal handler;
 * reliable SIGINT delivery to a detached child is not available on Windows.
 */

const assert = require('assert/strict');
const os = require('os');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');

const HARNESS = path.resolve(__dirname, 'lib', 'local-browser-harness.cjs');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// Is a PID alive? `process.kill(pid, 0)` throws ESRCH when the process is
// gone and EPERM when it exists but we may not signal it (treat EPERM as
// alive — it exists). Works on POSIX and Windows.
function isAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return err.code === 'EPERM';
  }
}

async function waitUntilDead(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!isAlive(pid)) return true;
    await sleep(50);
  }
  return !isAlive(pid);
}

// A tiny orchestrator: requires the REAL shared harness, spawns a
// long-lived grandchild, tracks it, installs signal handlers, prints
// `GRANDCHILD <pid>` then idles. `mode` selects which teardown path we
// drive after spawn — but both ultimately route through the same tracked
// kill-sweep; the script body is identical, only the parent's kill differs.
function orchestratorSource() {
  // The harness path is interpolated as a JSON string literal so Windows
  // backslashes survive into the child source.
  const harnessLit = JSON.stringify(HARNESS);
  return [
    "'use strict';",
    `const { createHarnessCleanup, spawnHarnessProcess } = require(${harnessLit});`,
    'const cleanup = createHarnessCleanup();',
    'cleanup.installSignalHandlers();',
    "const grandchild = cleanup.trackProcess(spawnHarnessProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: ['ignore','ignore','ignore'] }));",
    "process.stdout.write('GRANDCHILD ' + grandchild.pid + '\\n');",
    'setInterval(() => {}, 1000);',
  ].join('\n');
}

async function spawnOrchestrator() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-teardown-'));
  const scriptPath = path.join(dir, 'orchestrator.cjs');
  fs.writeFileSync(scriptPath, orchestratorSource(), 'utf8');

  const child = spawn(process.execPath, [scriptPath], {
    stdio: ['ignore', 'pipe', 'inherit'],
  });

  // Read the grandchild PID from the orchestrator's first stdout line.
  const grandchildPid = await new Promise((resolve, reject) => {
    let buf = '';
    const onData = (chunk) => {
      buf += chunk.toString('utf8');
      const m = buf.match(/GRANDCHILD (\d+)/);
      if (m) {
        child.stdout.off('data', onData);
        resolve(Number(m[1]));
      }
    };
    child.stdout.on('data', onData);
    child.once('error', reject);
    setTimeout(() => reject(new Error('orchestrator never reported its grandchild PID')), 15000);
  });

  return { child, grandchildPid, cleanupDir: () => {
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) {}
  } };
}

// Core invariant (cross-platform): when the orchestrator dies, its tracked
// grandchild "server" dies too. We terminate via child.kill('SIGTERM');
// on POSIX the SIGTERM handler runs cleanup() then exits; on Windows
// child.kill maps to TerminateProcess and the 'exit' handler's cleanupSync
// reaps the tracked grandchild. Either way the grandchild MUST NOT survive.
test('orchestrator teardown reaps its tracked grandchild on termination', async () => {
  const { child, grandchildPid, cleanupDir } = await spawnOrchestrator();
  try {
    assert.ok(isAlive(grandchildPid), 'grandchild should be alive after spawn');

    const orchestratorExit = new Promise((resolve) => child.once('exit', resolve));
    child.kill('SIGTERM');

    // Orchestrator must exit promptly.
    const exited = await Promise.race([
      orchestratorExit.then(() => true),
      sleep(15000).then(() => false),
    ]);
    assert.ok(exited, 'orchestrator process should exit after SIGTERM');

    // The tracked grandchild must be reaped — generous timeout because on
    // Windows the sweep goes through `taskkill /T /F` (spawnSync), which is
    // multi-second on a loaded host.
    const dead = await waitUntilDead(grandchildPid, 30000);
    assert.ok(
      dead,
      `grandchild ${grandchildPid} was orphaned — the teardown sweep did ` +
        `not reap the tracked server when the orchestrator was terminated.`,
    );
  } finally {
    if (isAlive(grandchildPid)) {
      // Defensive: never leave a stray grandchild if the assertion failed.
      try { process.kill(grandchildPid, 'SIGKILL'); } catch (_) {}
    }
    if (!child.killed && child.exitCode == null) {
      try { child.kill('SIGKILL'); } catch (_) {}
    }
    cleanupDir();
  }
});

// POSIX-only: assert the SIGINT signal-handler arm specifically. On Windows
// there is no JS SIGINT delivery to a non-console child, so this arm is
// skipped; the platform-neutral termination case above covers the outcome.
test('orchestrator teardown reaps its grandchild on SIGINT (POSIX)', async () => {
  if (process.platform === 'win32') {
    console.log('  SKIP (Windows: JS SIGINT delivery to a child is not reliable)');
    return;
  }
  const { child, grandchildPid, cleanupDir } = await spawnOrchestrator();
  try {
    assert.ok(isAlive(grandchildPid), 'grandchild should be alive after spawn');
    const orchestratorExit = new Promise((resolve) => child.once('exit', resolve));
    child.kill('SIGINT');
    await Promise.race([orchestratorExit, sleep(15000)]);
    const dead = await waitUntilDead(grandchildPid, 15000);
    assert.ok(dead, `grandchild ${grandchildPid} survived a SIGINT to the orchestrator`);
  } finally {
    if (isAlive(grandchildPid)) {
      try { process.kill(grandchildPid, 'SIGKILL'); } catch (_) {}
    }
    if (!child.killed && child.exitCode == null) {
      try { child.kill('SIGKILL'); } catch (_) {}
    }
    cleanupDir();
  }
});

(async () => {
  let failed = 0;
  for (const { name, fn } of tests) {
    try {
      await fn();
      console.log(`PASS ${name}`);
    } catch (err) {
      failed += 1;
      console.error(`FAIL ${name}`);
      console.error(err && err.stack ? err.stack : err);
    }
  }
  if (failed > 0) {
    console.error(`orchestrator-teardown-integration tests: ${failed} failed.`);
    process.exit(1);
  }
  console.log(`orchestrator-teardown-integration tests: ${tests.length} passed.`);
})().catch((err) => {
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
