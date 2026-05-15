'use strict';

const { spawn, spawnSync } = require('child_process');
const http = require('http');

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function probeHttp(port, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const path = opts.path || '/';
  const timeout = opts.timeout || 1000;

  return new Promise((resolve) => {
    const req = http.get({ host, port, path, timeout }, (res) => {
      res.resume();
      resolve(res.statusCode != null);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

async function waitForHttpReady(port, deadline, opts = {}) {
  const pollMs = opts.pollMs || 200;
  const isAborted = opts.isAborted || (() => false);
  while (Date.now() < deadline) {
    if (isAborted()) return false;
    if (await probeHttp(port, opts)) return true;
    if (isAborted()) return false;
    await sleep(pollMs);
  }
  return false;
}

function spawnHarnessProcess(command, args, opts = {}) {
  const spawnOpts = { ...opts };
  if (spawnOpts.detached == null && process.platform !== 'win32') {
    // Make the child a process-group leader so teardown can kill the full
    // server/browser subtree instead of only the direct Node child.
    spawnOpts.detached = true;
  }
  return spawn(command, args, spawnOpts);
}

function hasExited(child) {
  return !child ||
    !child.pid ||
    child.exitCode != null ||
    child.signalCode != null;
}

function waitForExit(child, timeoutMs) {
  if (hasExited(child)) return Promise.resolve(true);
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      child.off('exit', onExit);
      resolve(false);
    }, timeoutMs);
    const onExit = () => {
      clearTimeout(timer);
      resolve(true);
    };
    child.once('exit', onExit);
  });
}

function terminateProcessTreeSync(child, opts = {}) {
  if (hasExited(child)) return;
  const signal = opts.signal || 'SIGTERM';
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/pid', String(child.pid), '/T', '/F'], {
      stdio: 'ignore',
      windowsHide: true,
    });
    return;
  }

  try {
    process.kill(-child.pid, signal);
  } catch (_) {
    try {
      child.kill(signal);
    } catch (__) {}
  }
}

async function terminateProcessTree(child, opts = {}) {
  if (hasExited(child)) return;

  terminateProcessTreeSync(child, { signal: opts.signal || 'SIGTERM' });
  if (await waitForExit(child, opts.timeoutMs || 2000)) return;

  if (process.platform !== 'win32') {
    terminateProcessTreeSync(child, { signal: 'SIGKILL' });
  } else {
    try {
      child.kill('SIGKILL');
    } catch (_) {}
  }
  await waitForExit(child, 500);
}

function createHarnessCleanup(opts = {}) {
  const processes = [];
  const cleanupFns = [];
  const onError = opts.onError || ((err) => {
    if (err) console.error(err && err.stack ? err.stack : err);
  });
  const exit = opts.exit || ((code) => process.exit(code));
  let cleaned = false;
  let cleaning = null;

  function trackProcess(child) {
    if (child) processes.push(child);
    return child;
  }

  function addCleanup(fn) {
    cleanupFns.push(fn);
    return fn;
  }

  function cleanupSync() {
    if (cleaned) return;
    cleaned = true;
    for (const child of [...processes].reverse()) {
      try {
        terminateProcessTreeSync(child);
      } catch (err) {
        onError(err);
      }
    }
    for (const fn of [...cleanupFns].reverse()) {
      try {
        fn();
      } catch (err) {
        onError(err);
      }
    }
  }

  async function cleanup() {
    if (cleaning) return cleaning;
    cleaning = (async () => {
      if (cleaned) return;
      cleaned = true;
      for (const child of [...processes].reverse()) {
        try {
          await terminateProcessTree(child);
        } catch (err) {
          onError(err);
        }
      }
      for (const fn of [...cleanupFns].reverse()) {
        try {
          await fn();
        } catch (err) {
          onError(err);
        }
      }
    })();
    return cleaning;
  }

  function installSignalHandlers() {
    process.once('SIGINT', () => {
      cleanup().then(() => exit(130), (err) => {
        onError(err);
        exit(1);
      });
    });
    process.once('SIGTERM', () => {
      cleanup().then(() => exit(143), (err) => {
        onError(err);
        exit(1);
      });
    });
    process.once('exit', cleanupSync);
  }

  return {
    addCleanup,
    cleanup,
    cleanupSync,
    installSignalHandlers,
    trackProcess,
  };
}

module.exports = {
  createHarnessCleanup,
  probeHttp,
  sleep,
  spawnHarnessProcess,
  terminateProcessTree,
  terminateProcessTreeSync,
  waitForHttpReady,
};
