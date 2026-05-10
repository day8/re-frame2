#!/usr/bin/env node
/*
 * Orchestrator: spawn http-server over out/browser-test on a port,
 * wait for it to be reachable, then run the Playwright runner.
 * Always tear the server down (success or failure).
 *
 * Why this wrapper exists: the bead suggested a shell one-liner of
 * `http-server ... & sleep 2 && node ...`. That works on POSIX but not
 * on Windows, and `sleep 2` is a brittle race. This script is the same
 * idea but cross-platform and with a real readiness probe.
 *
 * Port selection (rf2-nuv7):
 *   1. If $BROWSER_TEST_PORT is set, try that port. If it's busy, fall
 *      back to a free port chosen by the OS.
 *   2. If unset, default to 8021 (historical behaviour). If 8021 is
 *      busy, fall back to a free port chosen by the OS.
 *   3. Either way, log clearly which port was used and thread it
 *      through to the Playwright runner via $BROWSER_TEST_URL.
 *
 * Early-exit detection: listen for the http-server child's `'exit'`
 * event during the readiness window. If it dies before becoming
 * reachable (typically EADDRINUSE), fail fast with a direct message
 * instead of waiting out the full readiness timeout.
 */

const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const path = require('path');

const DEFAULT_PORT = 8021;
const ROOT = path.resolve(__dirname, '..', 'out', 'browser-test');
const INDEX = path.join(ROOT, 'index.html');
const RUNNER = path.resolve(__dirname, 'run-browser-tests.cjs');
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;

// shadow-cljs's :browser-test target generates an index.html with an empty
// <body>. Some example namespaces (e.g. examples/reagent/nine_states/core.cljs) do
// `(rdc/create-root (js/document.getElementById "app"))` at namespace-load
// time. Without an `#app` element, React 18 throws and aborts the test
// runner before the cljs.test summary is printed. Patch the generated
// index.html to include a hidden mount point. Idempotent.
function ensureMountPoint() {
  if (!fs.existsSync(INDEX)) return;
  const html = fs.readFileSync(INDEX, 'utf8');
  if (html.includes('id="app"')) return;
  const patched = html.replace(
    '<body>',
    '<body><div id="app" style="display:none"></div>'
  );
  if (patched !== html) {
    fs.writeFileSync(INDEX, patched, 'utf8');
    console.log(`Patched ${INDEX} with <div id="app"> mount point.`);
  }
}

// True if the given TCP port is currently free on 127.0.0.1.
// We bind a temporary listener and immediately release it. EADDRINUSE
// on the bind attempt means it's busy; any other error is treated as
// "not free" so we fall back to OS-chosen port.
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

// Ask the OS for a free port (listen on 0, capture, close).
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

// Resolve the port to use, honouring $BROWSER_TEST_PORT first, then
// the historical default 8021, then a free OS-chosen port.
async function resolvePort() {
  const envRaw = process.env.BROWSER_TEST_PORT;
  if (envRaw && envRaw.trim() !== '') {
    const parsed = parseInt(envRaw, 10);
    if (!Number.isInteger(parsed) || parsed < 0 || parsed > 65535) {
      console.error(
        `BROWSER_TEST_PORT="${envRaw}" is not a valid TCP port; ignoring and choosing a free port.`
      );
      return await findFreePort();
    }
    if (await isPortFree(parsed)) {
      return parsed;
    }
    const fallback = await findFreePort();
    console.warn(
      `BROWSER_TEST_PORT=${parsed} is busy; falling back to free port ${fallback}.`
    );
    return fallback;
  }
  if (await isPortFree(DEFAULT_PORT)) {
    return DEFAULT_PORT;
  }
  const fallback = await findFreePort();
  console.warn(
    `Default port ${DEFAULT_PORT} is busy; falling back to free port ${fallback}. ` +
      `Set BROWSER_TEST_PORT to pin a specific port.`
  );
  return fallback;
}

function probe(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/', timeout: 1000 }, (res) => {
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

// Race-style readiness wait: resolves true on first successful probe,
// false on timeout, false-with-cause on early child exit. The caller
// distinguishes between timeout and early-exit via the supplied
// state object.
async function waitForReady(port, deadline, state) {
  while (Date.now() < deadline) {
    if (state.exited) return false;
    if (await probe(port)) return true;
    if (state.exited) return false;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return false;
}

(async () => {
  ensureMountPoint();

  const port = await resolvePort();
  console.log(`Serving ${ROOT} on http://127.0.0.1:${port}`);

  const isWin = process.platform === 'win32';
  // On Windows, npx is a .cmd shim — must go through the shell.
  const httpServerCmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['http-server', ROOT, '-p', String(port), '-s', '-c-1'];
  const server = spawn(httpServerCmd, args, {
    stdio: ['ignore', 'inherit', 'inherit'],
    shell: isWin,
  });

  // Track the server's lifecycle so the readiness loop can fail fast
  // if http-server dies before becoming reachable. With pre-binding via
  // isPortFree() this should be rare, but a slow-to-release socket or a
  // race against a sibling spawner can still trigger EADDRINUSE.
  const state = { exited: false, exitCode: null, exitSignal: null };
  server.on('exit', (code, signal) => {
    state.exited = true;
    state.exitCode = code;
    state.exitSignal = signal;
  });

  const ready = await waitForReady(port, Date.now() + READY_TIMEOUT_MS, state);
  if (!ready) {
    if (state.exited) {
      console.error(
        `http-server exited before becoming reachable on :${port} ` +
          `(code=${state.exitCode}, signal=${state.exitSignal}). ` +
          `Likely cause: port already in use or http-server failed to start.`
      );
    } else {
      console.error(
        `http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`
      );
      server.kill();
    }
    process.exit(1);
  }

  const runner = spawn(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: { ...process.env, BROWSER_TEST_URL: `http://127.0.0.1:${port}` },
  });

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  if (!state.exited) {
    server.kill();
  }
  process.exit(code == null ? 1 : code);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
