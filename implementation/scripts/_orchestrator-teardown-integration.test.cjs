#!/usr/bin/env node

'use strict';

/*
 * Runtime check behind _orchestrator-teardown-policy.test.cjs: a tiny orchestrator
 * using the REAL shared harness spawns and tracks a long-lived grandchild; when the
 * orchestrator is terminated, the grandchild must not survive it. SIGINT gets its
 * own case on POSIX only, where it can be delivered to a detached child.
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

// EPERM means the process exists but may not be signalled.
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

function orchestratorSource() {
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

// SIGTERM runs the handler on POSIX; on Windows it is TerminateProcess and the
// 'exit' handler's sweep reaps the grandchild.
test('orchestrator teardown reaps its tracked grandchild on termination', async () => {
  const { child, grandchildPid, cleanupDir } = await spawnOrchestrator();
  try {
    assert.ok(isAlive(grandchildPid), 'grandchild should be alive after spawn');

    const orchestratorExit = new Promise((resolve) => child.once('exit', resolve));
    child.kill('SIGTERM');

    const exited = await Promise.race([
      orchestratorExit.then(() => true),
      sleep(15000).then(() => false),
    ]);
    assert.ok(exited, 'orchestrator process should exit after SIGTERM');

    // Generous: on Windows the sweep is a multi-second `taskkill /T /F`.
    const dead = await waitUntilDead(grandchildPid, 30000);
    assert.ok(
      dead,
      `grandchild ${grandchildPid} was orphaned — the teardown sweep did ` +
        `not reap the tracked server when the orchestrator was terminated.`,
    );
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
