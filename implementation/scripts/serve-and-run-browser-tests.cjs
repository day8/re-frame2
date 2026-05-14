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
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const net = require('net');
const path = require('path');
const { enforcePolicy, DEFAULT_OUT_ROOT } = require('./_path-policy.cjs');

const DEFAULT_PORT = 8021;
// $BROWSER_TEST_ROOT lets a caller point the orchestrator at a different
// shadow-cljs :browser-test output directory — used by the prod-mode
// schemas boundary build (Spec 010 §Production builds, rf2-r2uh /
// rf2-84e9), whose `:advanced + goog.DEBUG=false` bundle lands in
// `out/browser-test-schemas-boundary-prod/` rather than the default
// `out/browser-test/`.
//
// Per rf2-o38lb (security audit): the override MUST land inside
// `implementation/out` unless `RE_FRAME_ALLOW_OUT_OF_TREE_WRITES=1` is
// set in the environment. The orchestrator writes `${ROOT}/index.html`
// and `${ROOT}/.rf-harness-token`; an unconstrained env-var override
// would otherwise become an arbitrary file-write primitive in CI or
// downstream environments inheriting parent env state.
const ROOT_OVERRIDE = process.env.BROWSER_TEST_ROOT;
const ROOT = enforcePolicy(
  'BROWSER_TEST_ROOT',
  ROOT_OVERRIDE || path.resolve(__dirname, '..', 'out', 'browser-test'),
  { allowedRoots: [DEFAULT_OUT_ROOT] },
);
const INDEX = path.join(ROOT, 'index.html');
const RUNNER = path.resolve(__dirname, 'run-browser-tests.cjs');
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;

// shadow-cljs's :browser-test target generates an index.html with an empty
// <body>. Some example namespaces (e.g. examples/reagent/nine_states/core.cljs)
// historically did `(rdc/create-root (js/document.getElementById "app"))` at
// namespace-load time. Per rf2-gkf9 the example mounts now defer `create-root`
// to their `run` fn, but the test harness still needs a single `#app` host so
// a future regression doesn't crash the runner before cljs.test prints its
// summary. Patch the generated index.html to include a hidden mount point.
// Idempotent.
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

// Server ownership token (rf2-gkf9). The readiness probe and the
// teardown path both verify that the server reachable on `port` is the
// one this orchestrator spawned. We do this by:
//
//   1. Generating a per-run nonce.
//   2. Writing it to a sentinel file under the served root BEFORE
//      spawning http-server (so the file is published as soon as
//      http-server starts serving the directory).
//   3. The readiness probe fetches `/.rf-harness-token` and compares
//      the body to the nonce — only then do we treat the server as
//      "ours" and proceed to the Playwright runner.
//   4. On teardown we tear down the PID we spawned and unlink the
//      sentinel file regardless of test outcome.
//
// This positively defeats two failure modes:
//   - An unrelated http-server (or any HTTP listener) on the same port
//     gives a 200 to `/` but does NOT have our sentinel — we fail fast
//     instead of running tests against the wrong asset tree.
//   - A stale http-server child from a previous aborted run that
//     re-bound the port between isPortFree() and our spawn — same
//     detection: its sentinel won't match this run's nonce.
const TOKEN_FILE_BASENAME = '.rf-harness-token';
const TOKEN_PATH = path.join(ROOT, TOKEN_FILE_BASENAME);

function writeOwnershipToken() {
  // Don't write if the target dir doesn't exist yet — let the script
  // error out elsewhere with a clearer message.
  if (!fs.existsSync(ROOT)) return null;
  const token = crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(TOKEN_PATH, token, 'utf8');
  return token;
}

function removeOwnershipToken() {
  try {
    if (fs.existsSync(TOKEN_PATH)) fs.unlinkSync(TOKEN_PATH);
  } catch (_) {
    // best-effort cleanup; do not let teardown bookkeeping fail the run.
  }
}

function fetchToken(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: `/${TOKEN_FILE_BASENAME}`, timeout: 1000 },
      (res) => {
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
      },
    );
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
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

// Race-style readiness wait: resolves true once the server on `port`
// answers AND its ownership token matches `expectedToken`. Resolves
// false on timeout, on early child exit, or if the token mismatches
// after the server becomes reachable (signals we're talking to a
// foreign server that happens to be bound to the same port — refuse to
// proceed). The caller distinguishes timeout vs early-exit vs
// foreign-server via the state object and the returned reason.
async function waitForReady(port, expectedToken, deadline, state) {
  let sawReachable = false;
  while (Date.now() < deadline) {
    if (state.exited) return { ok: false, reason: 'child-exited' };
    if (await probe(port)) {
      sawReachable = true;
      if (state.exited) return { ok: false, reason: 'child-exited' };
      const got = await fetchToken(port);
      if (got && got === expectedToken) {
        return { ok: true };
      }
      if (got && got !== expectedToken) {
        return { ok: false, reason: 'token-mismatch', got };
      }
      // token absent yet — http-server may have started but not yet
      // be serving the sentinel file. Keep polling.
    }
    if (state.exited) return { ok: false, reason: 'child-exited' };
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return { ok: false, reason: sawReachable ? 'token-never-served' : 'timeout' };
}

(async () => {
  ensureMountPoint();

  // Publish the per-run ownership token (rf2-gkf9) before spawning
  // http-server so the file is visible the moment the server starts
  // serving the directory. Cleaned up unconditionally in the finally
  // path below.
  const token = writeOwnershipToken();
  if (!token) {
    console.error(`Asset root missing: ${ROOT}. Did shadow-cljs compile run?`);
    process.exit(1);
  }

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

  // Shared teardown — kills the server we spawned (and only that one)
  // and removes the ownership token. Idempotent.
  const tornDownRef = { value: false };
  const teardown = () => {
    if (tornDownRef.value) return;
    tornDownRef.value = true;
    if (!state.exited) {
      try { server.kill(); } catch (_) {}
    }
    removeOwnershipToken();
  };
  process.on('exit', teardown);
  process.on('SIGINT', () => { teardown(); process.exit(130); });
  process.on('SIGTERM', () => { teardown(); process.exit(143); });

  const ready = await waitForReady(port, token, Date.now() + READY_TIMEOUT_MS, state);
  if (!ready.ok) {
    if (ready.reason === 'child-exited' || state.exited) {
      console.error(
        `http-server exited before becoming reachable on :${port} ` +
          `(code=${state.exitCode}, signal=${state.exitSignal}). ` +
          `Likely cause: port already in use or http-server failed to start.`
      );
    } else if (ready.reason === 'token-mismatch') {
      console.error(
        `A server is reachable on :${port}, but its /${TOKEN_FILE_BASENAME} ` +
          `does not match this run's ownership token. Refusing to run tests ` +
          `against a server this harness did not launch. ` +
          `(got "${ready.got}", expected "${token}"). ` +
          `Set BROWSER_TEST_PORT to pin a different port if this is intentional.`
      );
    } else if (ready.reason === 'token-never-served') {
      console.error(
        `http-server on :${port} became reachable but never served ` +
          `/${TOKEN_FILE_BASENAME} within ${READY_TIMEOUT_MS}ms. ` +
          `Asset root may be inconsistent.`
      );
    } else {
      console.error(
        `http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`
      );
    }
    teardown();
    process.exit(1);
  }

  const runner = spawn(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: { ...process.env, BROWSER_TEST_URL: `http://127.0.0.1:${port}` },
  });

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  teardown();
  process.exit(code == null ? 1 : code);
})().catch((err) => {
  console.error(err);
  removeOwnershipToken();
  process.exit(1);
});
