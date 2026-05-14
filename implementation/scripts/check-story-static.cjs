#!/usr/bin/env node
/*
 * Story static-export sanity check (rf2-8wgpm).
 *
 * 1. Runs `story-build.cjs` to produce the static export at
 *    `implementation/out/story-static/counter-with-stories/`.
 * 2. Selects a free local port and serves the output directory via
 *    http-server. The previous implementation hardcoded port 8040 and
 *    treated any responder on that port as "ready"; rf2-o38lb (security
 *    audit) called that out as a TOCTOU window for a port-squatter to
 *    serve foreign content to the headless browser. This script now
 *    mirrors the hardened ownership-token model from
 *    `serve-and-run-browser-tests.cjs`:
 *
 *      - Free-port selection (OS-chosen if PORT env var is busy).
 *      - Per-run ownership token published as `/.rf-harness-token`.
 *      - Child-exit detection during readiness.
 *      - Token-fetch verification before Playwright starts driving the
 *        browser — refuses to proceed if the server reachable on
 *        the chosen port is not the one we spawned.
 *
 * 3. Drives a headless Chromium against the resolved base URL and
 *    verifies the Story shell mounted (the canonical "Select a variant
 *    or workspace" placeholder is rendered, and the chrome landmarks
 *    are present).
 * 4. Verifies the first-visit help overlay is suppressed (per
 *    spec/013 §Static-mode runtime semantics — visitors arriving at a
 *    published docs site don't get the dev-time onboarding modal).
 * 5. Tears the server down and removes the ownership token sentinel.
 *
 * Per rf2-o38lb: env-var driven defaults are constrained to
 * `implementation/out/` unless the explicit opt-in flag
 * `RE_FRAME_ALLOW_OUT_OF_TREE_WRITES=1` is set in the environment.
 * The audit's secondary finding was that env-controlled path overrides
 * could become an arbitrary file-write primitive in CI / downstream
 * environments inheriting state from a wrapper.
 */

'use strict';

const { spawn, spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const net = require('net');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const OUT_DIR = path.join(
  IMPL_ROOT,
  'out',
  'story-static',
  'counter-with-stories',
);
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const DEFAULT_PORT = 8040;
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;

// Per rf2-o38lb: ownership-token sentinel, same shape as
// serve-and-run-browser-tests.cjs.
const TOKEN_FILE_BASENAME = '.rf-harness-token';
const TOKEN_PATH = path.join(OUT_DIR, TOKEN_FILE_BASENAME);

function runBuild() {
  // Use process.execPath directly without shell:true — on Windows the
  // installed node.exe path commonly contains spaces ("Program Files"),
  // which the shell wrapper splits on. Skipping `shell` keeps the
  // argv pass-through verbatim.
  const script = path.join(__dirname, 'story-build.cjs');
  const result = spawnSync(process.execPath, [script], {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    throw new Error(`story-build.cjs failed (exit ${result.status})`);
  }
}

// True if the given TCP port is currently free on 127.0.0.1.
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

async function resolvePort() {
  if (await isPortFree(DEFAULT_PORT)) {
    return DEFAULT_PORT;
  }
  const fallback = await findFreePort();
  console.warn(
    `Default port ${DEFAULT_PORT} is busy; falling back to free port ${fallback}.`,
  );
  return fallback;
}

function writeOwnershipToken() {
  if (!fs.existsSync(OUT_DIR)) return null;
  const token = crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(TOKEN_PATH, token, 'utf8');
  return token;
}

function removeOwnershipToken() {
  try {
    if (fs.existsSync(TOKEN_PATH)) fs.unlinkSync(TOKEN_PATH);
  } catch (_) {
    // best-effort cleanup
  }
}

function probe(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/', timeout: 1000 },
      (res) => {
        res.resume();
        resolve(res.statusCode != null);
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
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

// Race-style readiness wait: resolves true once the server on `port`
// answers AND its ownership token matches `expectedToken`. Resolves
// false on timeout, on early child exit, or if the token mismatches —
// signals we're talking to a foreign server that happens to be bound
// to the same port; refuse to proceed against unowned content.
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
      // token absent yet — keep polling.
    }
    if (state.exited) return { ok: false, reason: 'child-exited' };
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return { ok: false, reason: sawReachable ? 'token-never-served' : 'timeout' };
}

async function smokeTest(baseUrl) {
  let playwright;
  try {
    playwright = require('playwright');
  } catch (e) {
    throw new Error(
      'playwright not installed — run `npm install --prefix implementation` first.',
    );
  }

  const browser = await playwright.chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  const consoleErrors = [];
  page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(`console.error: ${msg.text()}`);
  });

  try {
    await page.goto(baseUrl, { waitUntil: 'load', timeout: 30000 });

    // The shell renders the "Select a variant or workspace" placeholder
    // when no variant / workspace is selected. The Story chrome lands
    // around it: the sidebar lists the four counter variants + two
    // workspaces.
    await page
      .getByText(/Select a variant or workspace from the sidebar/i, { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 15000 });

    // Three landmarks (nav / main / aside) — same shape as the dev-mode
    // shell. Static-mode flips the dev-time affordances OFF; the chrome
    // structure is identical.
    await page.getByRole('navigation').waitFor({ state: 'visible', timeout: 5000 });
    await page.getByRole('main').waitFor({ state: 'visible', timeout: 5000 });
    await page
      .getByRole('complementary')
      .waitFor({ state: 'visible', timeout: 5000 });

    // The chrome-level toolbar renders. Per spec/010 the strip emits
    // `data-test="story-toolbar"`.
    await page
      .locator('[data-test="story-toolbar"]')
      .waitFor({ state: 'visible', timeout: 5000 });

    // First-visit help overlay must be SUPPRESSED in static mode (per
    // spec/013 §Static-mode runtime semantics). The dev-mode shell pops
    // a `role="dialog" aria-modal="true"` overlay on first paint; the
    // static-mode shell does not. We assert the overlay is absent ~1s
    // after first paint (enough for any `component-did-mount` race).
    await new Promise((r) => setTimeout(r, 1000));
    const dialogCount = await page.getByRole('dialog').count();
    if (dialogCount > 0) {
      throw new Error(
        `expected the first-visit help overlay to be suppressed in static mode, found ${dialogCount} dialog(s)`,
      );
    }

    // Click a variant from the sidebar — the canvas re-renders with
    // that variant's title, proving the registry survived `:advanced`
    // compilation and the dispatch / subscription path is wired.
    const navRow = page
      .getByRole('navigation')
      .getByText('/empty', { exact: false })
      .first();
    await navRow.waitFor({ state: 'visible', timeout: 10000 });
    await navRow.click();
    await page
      .getByText(':story.counter/empty', { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    if (consoleErrors.length > 0) {
      console.warn('console errors observed (non-fatal):');
      for (const e of consoleErrors) console.warn('  ' + e);
    }
  } finally {
    await browser.close();
  }
}

(async () => {
  // 1. Build.
  runBuild();

  // 2. Sanity-check the on-disk shape — the build script writes
  //    index.html + main.js + manifest.json next to a `cljs-runtime/`
  //    directory.
  for (const required of ['index.html', 'main.js', 'manifest.json']) {
    const p = path.join(OUT_DIR, required);
    if (!fs.existsSync(p)) {
      console.error(`Build output missing ${required} at ${p}`);
      process.exit(1);
    }
  }

  // 3. Publish the ownership token BEFORE spawning http-server.
  const token = writeOwnershipToken();
  if (!token) {
    console.error(`Asset root missing: ${OUT_DIR}`);
    process.exit(1);
  }

  // 4. Pick a port and serve.
  const port = await resolvePort();
  console.log(`Serving ${OUT_DIR} on http://127.0.0.1:${port}`);

  const server = spawn(
    process.execPath,
    [HTTP_SERVER_BIN, OUT_DIR, '-p', String(port), '-s', '-c-1'],
    { cwd: IMPL_ROOT, stdio: ['ignore', 'inherit', 'inherit'] },
  );

  // Track server lifecycle for fail-fast on early exit.
  const state = { exited: false, exitCode: null, exitSignal: null };
  server.on('exit', (code, signal) => {
    state.exited = true;
    state.exitCode = code;
    state.exitSignal = signal;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
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

  // 5. Wait for ready WITH ownership-token verification.
  const ready = await waitForReady(port, token, Date.now() + READY_TIMEOUT_MS, state);
  if (!ready.ok) {
    if (ready.reason === 'child-exited' || state.exited) {
      console.error(
        `http-server exited before becoming reachable on :${port} ` +
          `(code=${state.exitCode}, signal=${state.exitSignal}).`,
      );
    } else if (ready.reason === 'token-mismatch') {
      console.error(
        `A server is reachable on :${port}, but its /${TOKEN_FILE_BASENAME} ` +
          `does not match this run's ownership token. Refusing to drive the ` +
          `browser against a server this harness did not launch. ` +
          `(got "${ready.got}", expected "${token}").`,
      );
    } else if (ready.reason === 'token-never-served') {
      console.error(
        `http-server on :${port} became reachable but never served ` +
          `/${TOKEN_FILE_BASENAME} within ${READY_TIMEOUT_MS}ms.`,
      );
    } else {
      console.error(
        `http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`,
      );
    }
    teardown();
    process.exit(1);
  }

  // 6. Smoke.
  let smokeError = null;
  try {
    await smokeTest(`http://127.0.0.1:${port}/`);
    console.log('story-static smoke passed.');
  } catch (err) {
    smokeError = err;
    console.error('story-static smoke failed:', err.message || err);
  }

  // 7. Tear down.
  teardown();
  process.exit(smokeError ? 1 : 0);
})().catch((err) => {
  console.error(err);
  removeOwnershipToken();
  process.exit(1);
});
