'use strict';

const { spawn, spawnSync } = require('child_process');
const http = require('http');
const net = require('net');

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// True if the given TCP port is currently free on 127.0.0.1. We bind a
// temporary listener and immediately release it; EADDRINUSE (or any
// other bind error) is treated as "not free" so callers fall back to an
// OS-chosen port. (rf2-84gzw — shared with serve-and-run-browser-tests
// and check-story-static, which carry their own copies for now.)
function isPortFree(port) {
  return new Promise((resolve) => {
    const srv = net.createServer();
    srv.once('error', () => resolve(false));
    srv.once('listening', () => {
      srv.close(() => resolve(true));
    });
    srv.listen(port, '127.0.0.1');
  });
}

// Ask the OS for a free port (listen on 0, capture, release).
function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

// Resolve a port to serve on: prefer `preferredPort` when it is free,
// otherwise fall back to an OS-chosen free port. `onFallback(preferred,
// fallback)` is invoked (if provided) when the preferred port is busy so
// callers can log the substitution.
async function resolveServePort(preferredPort, opts = {}) {
  if (Number.isInteger(preferredPort) && (await isPortFree(preferredPort))) {
    return preferredPort;
  }
  const fallback = await findFreePort();
  if (typeof opts.onFallback === 'function') {
    opts.onFallback(preferredPort, fallback);
  }
  return fallback;
}

// Fetch the body of a path (default `/.rf-harness-token`) from a local
// server, trimmed. Resolves null on non-200, error, or timeout — the
// caller treats "no token yet" as "keep polling" and a present-but-wrong
// token as a foreign server. (rf2-84gzw / rf2-gkf9 ownership handshake.)
const TOKEN_FILE_BASENAME = '.rf-harness-token';

function fetchToken(port, opts = {}) {
  const host = opts.host || '127.0.0.1';
  const tokenPath = opts.path || `/${TOKEN_FILE_BASENAME}`;
  const timeout = opts.timeout || 1000;
  return new Promise((resolve) => {
    const req = http.get({ host, port, path: tokenPath, timeout }, (res) => {
      if (res.statusCode !== 200) {
        res.resume();
        resolve(null);
        return;
      }
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve(body.trim()));
      res.on('error', () => resolve(null));
    });
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}

// Readiness wait WITH ownership-token verification (rf2-84gzw). Resolves
// `{ ok: true }` only once the server on `port` answers AND serves a
// `/.rf-harness-token` body equal to `expectedToken`. Otherwise resolves
// `{ ok: false, reason }`:
//   - 'child-exited'      — opts.isAborted() went true (server died)
//   - 'token-mismatch'    — a foreign server is bound to this port (its
//                           token is present but != ours); refuse to run
//   - 'token-never-served'— reachable but never published our token
//   - 'timeout'           — never became reachable
// This positively distinguishes "our server" from a port-squatter / a
// stale http-server from an aborted run, defeating false-RED and (worse)
// false-GREEN runs against the wrong asset tree.
async function waitForOwnedHttpReady(port, expectedToken, deadline, opts = {}) {
  const pollMs = opts.pollMs || 200;
  const isAborted = opts.isAborted || (() => false);
  let sawReachable = false;
  while (Date.now() < deadline) {
    if (isAborted()) return { ok: false, reason: 'child-exited' };
    if (await probeHttp(port, opts)) {
      sawReachable = true;
      if (isAborted()) return { ok: false, reason: 'child-exited' };
      const got = await fetchToken(port, opts);
      if (got && got === expectedToken) return { ok: true };
      if (got && got !== expectedToken) {
        return { ok: false, reason: 'token-mismatch', got };
      }
      // Token absent yet — http-server may be up but not yet serving the
      // sentinel file. Keep polling.
    }
    if (isAborted()) return { ok: false, reason: 'child-exited' };
    await sleep(pollMs);
  }
  return { ok: false, reason: sawReachable ? 'token-never-served' : 'timeout' };
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
  // Injectable terminators (default: the real process-tree killers).
  // The seam exists so the rf2-ogpeq race can be unit-tested
  // deterministically — a test can supply a synchronous terminator that
  // records which children were swept and an async one that never
  // resolves (modelling an in-flight cleanup that a hard exit abandons),
  // without depending on real (and, on Windows, multi-second) taskkill
  // latency.
  const terminateSync = opts.terminateSync || terminateProcessTreeSync;
  const terminateAsync = opts.terminateAsync || terminateProcessTree;
  // Two independent guards (rf2-ogpeq). The synchronous kill-sweep over
  // the tracked children and the cleanupFns each gate on their OWN flag,
  // so the async `cleanup()` setting one cannot suppress the other on a
  // hard-exit race:
  //
  //   - `killed` guards the child-process kill-sweep. cleanupSync() will
  //     ALWAYS run the synchronous sweep unless a previous sweep already
  //     ran (the sweep is itself idempotent — terminateProcessTreeSync
  //     no-ops on already-dead children via hasExited()). This is what
  //     stops orphaned children when process.exit() fires the 'exit'
  //     handler while an async cleanup() is mid-flight (awaiting
  //     terminateProcessTree on child N of M).
  //   - `cleanupFnsRan` guards the addCleanup callbacks so they run at
  //     most once across both paths.
  let killed = false;
  let cleanupFnsRan = false;
  let cleaning = null;

  function trackProcess(child) {
    if (child) processes.push(child);
    return child;
  }

  function addCleanup(fn) {
    cleanupFns.push(fn);
    return fn;
  }

  // Synchronously terminate every tracked child subtree. Idempotent:
  // the per-child terminateProcessTreeSync no-ops on already-exited
  // children, and the `killed` flag short-circuits a redundant sweep.
  function killChildrenSync() {
    if (killed) return;
    killed = true;
    for (const child of [...processes].reverse()) {
      try {
        terminateSync(child);
      } catch (err) {
        onError(err);
      }
    }
  }

  function runCleanupFnsSync() {
    if (cleanupFnsRan) return;
    cleanupFnsRan = true;
    for (const fn of [...cleanupFns].reverse()) {
      try {
        fn();
      } catch (err) {
        onError(err);
      }
    }
  }

  // The process 'exit' handler. Node cannot await here, so a hard exit
  // racing an in-flight async cleanup() MUST still synchronously kill
  // any children that cleanup() has not yet reached — otherwise they are
  // orphaned (rf2-ogpeq). killChildrenSync gates on its own `killed`
  // flag, never on the async path's state, so this always sweeps unless
  // a sync sweep already completed.
  function cleanupSync() {
    killChildrenSync();
    runCleanupFnsSync();
  }

  async function cleanup() {
    if (cleaning) return cleaning;
    cleaning = (async () => {
      for (const child of [...processes].reverse()) {
        // hasExited() makes this a no-op if cleanupSync() already swept
        // this child (or it exited on its own).
        try {
          await terminateAsync(child);
        } catch (err) {
          onError(err);
        }
      }
      // Mark the synchronous kill-sweep satisfied so a subsequent
      // 'exit' -> cleanupSync() doesn't redundantly re-sweep children
      // this async path already awaited to completion.
      killed = true;
      if (cleanupFnsRan) return;
      cleanupFnsRan = true;
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
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  fetchToken,
  findFreePort,
  isPortFree,
  probeHttp,
  resolveServePort,
  sleep,
  spawnHarnessProcess,
  terminateProcessTree,
  terminateProcessTreeSync,
  waitForHttpReady,
  waitForOwnedHttpReady,
};
