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
 *    verifies the Story shell mounted (the empty-state canvas
 *    `data-test="story-canvas-empty"` is rendered, and the chrome landmarks
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

const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const {
  createDiagnosticBuffer,
  isVerboseTests,
} = require('./lib/browser-test-report.cjs');
const {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  resolveServePort,
  spawnHarnessProcess,
  waitForOwnedHttpReady,
} = require('./lib/local-browser-harness.cjs');

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
const VERBOSE_TESTS = isVerboseTests();
const diagnostics = createDiagnosticBuffer();
const cleanup = createHarnessCleanup({
  onError: (err) => diagnostics.add(err && err.stack ? err.stack : String(err), 'stderr'),
});
cleanup.addCleanup(() => {
  diagnostics.add('Tearing down story-static http-server.');
  removeOwnershipToken();
});
cleanup.installSignalHandlers();

// Per rf2-o38lb: ownership-token sentinel, same shape as
// serve-and-run-browser-tests.cjs. The basename + the probe/token-fetch/
// owned-readiness mechanics come from the shared local-browser-harness.cjs;
// this script only owns the per-run token write/cleanup.
const TOKEN_PATH = path.join(OUT_DIR, TOKEN_FILE_BASENAME);

function addChunk(diagnostics, prefix, chunk, stream = 'stdout') {
  const normalized = String(chunk || '').replace(/\r\n/g, '\n');
  for (const line of normalized.split('\n')) {
    if (line.length === 0) continue;
    diagnostics.add(`${prefix}${line}`, stream);
  }
}

function flushDiagnostics(diagnostics) {
  if (diagnostics.isEmpty()) return;
  console.error('--- story-static diagnostics ---');
  diagnostics.flush({
    stdout: (line) => console.error(line),
    stderr: (line) => console.error(line),
  });
  console.error('--------------------------------');
}

// A sentinel absolute checkout root, injected as RF2_TESTBED_PROJECT_ROOT
// into the static build's environment. The static export MUST NOT consume
// this env var (the dev-testbed `checkout-root` goog-define is not seeded for the
// :story-static/* build), so this string must NOT survive into the emitted
// bundle or manifest. `assertNoSentinelLeak` below enforces that.
const PROJECT_ROOT_SENTINEL =
  'C:/Users/rf2-static-leak-sentinel/code/should-not-be-bundled';

function runBuild(diagnostics) {
  // Use process.execPath directly without shell:true — on Windows the
  // installed node.exe path commonly contains spaces ("Program Files"),
  // which the shell wrapper splits on. Skipping `shell` keeps the
  // argv pass-through verbatim.
  const script = path.join(__dirname, 'story-build.cjs');
  diagnostics.add(`Process: ${process.execPath} ${script}`);
  // Inject the sentinel checkout root the same way a dev launcher would
  // (`RF2_TESTBED_PROJECT_ROOT`). The static-export build deliberately does
  // NOT seed `re-frame.testbed.config/checkout-root` from this env var, so the
  // sentinel must not appear anywhere in the published artifact — the
  // self-containment contract (a published bundle carries no build-machine
  // checkout path). `assertNoSentinelLeak` verifies it post-build.
  const result = spawnSync(process.execPath, [script], {
    cwd: IMPL_ROOT,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, RF2_TESTBED_PROJECT_ROOT: PROJECT_ROOT_SENTINEL },
  });
  if (result.stdout) addChunk(diagnostics, '[story-build:stdout] ', result.stdout);
  if (result.stderr) addChunk(diagnostics, '[story-build:stderr] ', result.stderr, 'stderr');
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`story-build.cjs failed (exit ${result.status})`);
  }
  diagnostics.add(`story-build.cjs exited ${result.status}`);
}

// Static-export self-containment: scan the emitted bundle + manifest for the
// build-time RF2_TESTBED_PROJECT_ROOT sentinel. The published export must
// carry NO ambient machine-local checkout path baked in. The advanced bundle
// inlines goog-define string constants, so a regression that re-seeds
// `re-frame.testbed.config/checkout-root` from the env var would surface here as
// the sentinel string embedded in main.js.
function assertNoSentinelLeak(diagnostics) {
  const filesToScan = ['main.js', 'manifest.json', 'index.html'];
  for (const fileName of filesToScan) {
    const p = path.join(OUT_DIR, fileName);
    if (!fs.existsSync(p)) continue;
    const contents = fs.readFileSync(p, 'utf8');
    if (contents.includes(PROJECT_ROOT_SENTINEL)) {
      throw new Error(
        `static-export self-containment violated: the build-machine checkout ` +
          `sentinel ("${PROJECT_ROOT_SENTINEL}") leaked into ${fileName}. The ` +
          `:story-static/* build must NOT seed re-frame.testbed.config/checkout-root ` +
          `from RF2_TESTBED_PROJECT_ROOT — a published bundle carries no ` +
          `machine-local checkout path.`,
      );
    }
    diagnostics.add(`Sentinel-leak scan clean: ${fileName}`);
  }
}

// Resolve the port via the shared harness primitive: prefer DEFAULT_PORT
// when free, else fall back to an OS-chosen free port (rf2-84gzw). The
// shared resolveServePort carries the strict 1..65535 explicit-port
// contract (rf2-0u8kz); the launcher-specific fallback note is logged
// into the diagnostics buffer.
async function resolvePort(diagnostics) {
  return await resolveServePort(DEFAULT_PORT, {
    onFallback: (preferred, fallback) => {
      diagnostics.add(
        `Default port ${preferred} is busy; falling back to free port ${fallback}.`,
      );
    },
  });
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

async function smokeTest(baseUrl, diagnostics) {
  let playwright;
  try {
    playwright = require('playwright');
  } catch (e) {
    throw new Error(
      'playwright not installed — run `npm install --prefix implementation` first.',
    );
  }

  const browser = await playwright.chromium.launch();
  cleanup.addCleanup(async () => {
    try {
      await browser.close();
    } catch (_) {}
  });
  const context = await browser.newContext();
  const page = await context.newPage();

  // rf2-mwx08: track uncaught browser/runtime exceptions SEPARATELY from
  // console noise. The static-export smoke passes when its visible
  // assertions resolve; previously an uncaught `pageerror` was
  // diagnostic-only, so the smoke could ship green while the page threw a
  // runtime exception the assertions happened not to cover. Console
  // output stays diagnostic-only; only `pageerror` is fatal. Mirrors the
  // rf2-wf5al fix for the examples/scripts Story play runner.
  const pageErrors = [];
  diagnostics.add('Spec: story-static static export smoke');
  diagnostics.add(`URL: ${baseUrl}`);
  page.on('pageerror', (err) => {
    pageErrors.push(err.stack || err.message);
    diagnostics.add(`[browser:pageerror] ${err.message}`, 'stderr');
    if (err.stack) diagnostics.add(err.stack, 'stderr');
  });
  page.on('console', (msg) => {
    diagnostics.add(`[browser:${msg.type()}] ${msg.text()}`);
  });
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) {
      diagnostics.add(`[browser:navigation] ${frame.url()}`);
    }
  });

  try {
    await page.goto(baseUrl, { waitUntil: 'load', timeout: 30000 });

    // The shell renders its empty-state canvas ("No variant selected" +
    // a pick-from-the-sidebar hint) when no variant / workspace is selected.
    // The Story chrome lands around it: the sidebar lists the four counter
    // variants + two workspaces. Asserted via the stable
    // `data-test="story-canvas-empty"` attribute (shell.cljs) rather than the
    // placeholder PROSE, which has drifted before — the test-id is the
    // contract, the copy is not.
    await page
      .locator('[data-test="story-canvas-empty"]')
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

    // rf2-mwx08: all visible assertions passed — but an uncaught
    // `pageerror` is still fatal. A green smoke that ignored a runtime
    // exception is a false-green. Allow a brief settle for any
    // post-interaction pageerror to surface, then fail if any were seen.
    await new Promise((r) => setTimeout(r, 200));
    if (pageErrors.length > 0) {
      throw new Error(
        `story-static smoke assertions passed, but the page emitted ` +
          `${pageErrors.length} uncaught pageerror(s) — failing the smoke ` +
          `(rf2-mwx08). First: ${pageErrors[0]}`,
      );
    }

  } finally {
    await browser.close();
  }
}

(async () => {
  // 1. Build.
  runBuild(diagnostics);

  // 2. Sanity-check the on-disk shape — the build script writes
  //    index.html + main.js + manifest.json next to a `cljs-runtime/`
  //    directory.
  for (const required of ['index.html', 'main.js', 'manifest.json']) {
    const p = path.join(OUT_DIR, required);
    if (!fs.existsSync(p)) {
      console.error(`Build output missing ${required} at ${p}`);
      flushDiagnostics(diagnostics);
      process.exit(1);
    }
  }

  // 2b. Static-export self-containment: the build ran with a sentinel
  //     RF2_TESTBED_PROJECT_ROOT; assert it did NOT leak into the bundle.
  try {
    assertNoSentinelLeak(diagnostics);
  } catch (err) {
    console.error(err.message || err);
    flushDiagnostics(diagnostics);
    process.exit(1);
  }

  // 3. Publish the ownership token BEFORE spawning http-server.
  const token = writeOwnershipToken();
  if (!token) {
    console.error(`Asset root missing: ${OUT_DIR}`);
    flushDiagnostics(diagnostics);
    process.exit(1);
  }

  // 4. Pick a port and serve.
  const port = await resolvePort(diagnostics);
  diagnostics.add(`Serving ${OUT_DIR} on http://127.0.0.1:${port}`);

  // Bind 127.0.0.1 (not http-server's 0.0.0.0 default): the readiness
  // probe and the headless browser only ever hit loopback, so the
  // listener must not be exposed on non-loopback interfaces during a
  // test run (rf2-utvst; matches serve-and-run-examples-tests.cjs).
  const serverArgs = [HTTP_SERVER_BIN, OUT_DIR, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'];
  const server = cleanup.trackProcess(spawnHarnessProcess(
    process.execPath,
    serverArgs,
    { cwd: IMPL_ROOT, stdio: ['ignore', 'pipe', 'pipe'] },
  ));
  diagnostics.add(
    `Process: ${process.execPath} ${serverArgs.join(' ')}`,
  );
  server.stdout.on('data', (d) => addChunk(diagnostics, '[http-server:stdout] ', d));
  server.stderr.on('data', (d) => addChunk(diagnostics, '[http-server:stderr] ', d, 'stderr'));

  // Track server lifecycle for fail-fast on early exit.
  const state = { exited: false, exitCode: null, exitSignal: null };
  server.on('exit', (code, signal) => {
    state.exited = true;
    state.exitCode = code;
    state.exitSignal = signal;
    if (code !== 0 && code !== null) {
      diagnostics.add(`http-server exited unexpectedly (code=${code}, signal=${signal}).`, 'stderr');
    } else {
      diagnostics.add(`http-server exited (code=${code}, signal=${signal}).`);
    }
  });

  // 5. Wait for ready WITH ownership-token verification via the shared
  //    harness primitive (rf2-84gzw / rf2-gkf9).
  const ready = await waitForOwnedHttpReady(port, token, Date.now() + READY_TIMEOUT_MS, {
    pollMs: POLL_MS,
    isAborted: () => state.exited,
  });
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
    await cleanup.cleanup();
    flushDiagnostics(diagnostics);
    process.exit(1);
  }

  // 6. Smoke.
  let smokeError = null;
  try {
    await smokeTest(`http://127.0.0.1:${port}/`, diagnostics);
  } catch (err) {
    smokeError = err;
    console.error('story-static smoke failed:', err.message || err);
    if (err && err.stack) diagnostics.add(err.stack, 'stderr');
  }

  // 7. Tear down.
  await cleanup.cleanup();
  if (!smokeError) {
    if (VERBOSE_TESTS) flushDiagnostics(diagnostics);
    console.log('Story static smoke passed.');
  } else {
    flushDiagnostics(diagnostics);
  }
  process.exit(smokeError ? 1 : 0);
})().catch((err) => {
  console.error(err);
  if (err && err.stack) diagnostics.add(err.stack, 'stderr');
  cleanup.cleanup().then(() => {
    flushDiagnostics(diagnostics);
    process.exit(1);
  }, (cleanupErr) => {
    console.error(cleanupErr && cleanupErr.stack ? cleanupErr.stack : cleanupErr);
    flushDiagnostics(diagnostics);
    process.exit(1);
  });
});
