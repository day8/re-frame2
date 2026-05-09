#!/usr/bin/env node
/*
 * Orchestrator: spawn http-server over out/browser-test on port 8021,
 * wait for it to be reachable, then run the Playwright runner.
 * Always tear the server down (success or failure).
 *
 * Why this wrapper exists: the bead suggested a shell one-liner of
 * `http-server ... & sleep 2 && node ...`. That works on POSIX but not
 * on Windows, and `sleep 2` is a brittle race. This script is the same
 * idea but cross-platform and with a real readiness probe.
 */

const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

const PORT = 8021;
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

async function waitForReady(port, deadline) {
  while (Date.now() < deadline) {
    if (await probe(port)) return true;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return false;
}

(async () => {
  ensureMountPoint();
  const isWin = process.platform === 'win32';
  // On Windows, npx is a .cmd shim — must go through the shell.
  const httpServerCmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['http-server', ROOT, '-p', String(PORT), '-s', '-c-1'];
  const server = spawn(httpServerCmd, args, {
    stdio: ['ignore', 'inherit', 'inherit'],
    shell: isWin,
  });

  let serverDown = false;
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForReady(PORT, Date.now() + READY_TIMEOUT_MS);
  if (!ready || serverDown) {
    console.error(`http-server did not become reachable on :${PORT} within ${READY_TIMEOUT_MS}ms.`);
    if (!serverDown) server.kill();
    process.exit(1);
  }

  const runner = spawn(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: { ...process.env, BROWSER_TEST_URL: `http://127.0.0.1:${PORT}` },
  });

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  if (!serverDown) {
    server.kill();
  }
  process.exit(code == null ? 1 : code);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
