#!/usr/bin/env node
/*
 * Narrow orchestrator for the occasional Story feature/load gate.
 *
 * Compiles only the Story testbeds (counter-with-stories + login-form)
 * needed by the quiet runner's specs — tools/story/test/
 * story_feature_load.cjs AND story_browser_scenarios.cjs — stages their
 * HTML files, serves implementation/out/examples, and invokes the
 * runner (run-story-feature-load-tests.cjs).
 */

const path = require('path');
const { resolveStoryFeatureLoadPort } = require('./story-feature-load-port.cjs');
const { prepareTestbeds } = require('./examples-staging.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  startLocalHttpServer,
} = require('../../implementation/scripts/lib/local-browser-harness.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'examples');
const RUNNER = path.resolve(__dirname, 'run-story-feature-load-tests.cjs');
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const READY_TIMEOUT_MS = 30000;
const VERBOSE = process.env.RF2_VERBOSE_TESTS === '1';
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

const TESTBEDS = [
  {
    build: 'examples/counter-with-stories',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'counter_with_stories', 'index.html'),
    outDir: path.join(OUT_ROOT, 'counter-with-stories'),
  },
  {
    build: 'examples/login-form',
    htmlSrc: path.join(REPO_ROOT, 'tools', 'story', 'testbeds', 'login_form', 'index.html'),
    outDir: path.join(OUT_ROOT, 'login-form'),
  },
];

async function main() {
  prepareTestbeds(TESTBEDS, { outRoot: OUT_ROOT, quiet: !VERBOSE });
  // Resolved after the compile, so a dev-http server a `watch` starts while
  // this run waits on the build lane cannot take the port between the probe
  // and the serve.
  const port = await resolveStoryFeatureLoadPort({ env: process.env, repoRoot: REPO_ROOT });

  // Serve implementation/out/examples on loopback. The shared harness owns
  // the http-server spawn, teardown tracking, early-exit abort, bounded
  // output capture (surfaced as the failure tail), and the readiness +
  // unreachable diagnostics. Bind 127.0.0.1 (not http-server's
  // 0.0.0.0 default): the runner and resolveStoryFeatureLoadPort only ever
  // touch loopback. Matches the adapter-smoke orchestrator.
  const { ready } = await startLocalHttpServer({
    cleanup,
    httpServerBin: HTTP_SERVER_BIN,
    root: OUT_ROOT,
    port,
    cwd: IMPL_ROOT,
    readyTimeoutMs: READY_TIMEOUT_MS,
    captureOutput: true,
    verbose: VERBOSE,
  });
  if (!ready) return 1;

  const runner = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      STORY_FEATURE_LOAD_BASE_URL: `http://127.0.0.1:${port}`,
    },
  }));

  const code = await new Promise((resolve) => runner.on('exit', resolve));

  return code == null ? 1 : code;
}

main().then(async (code) => {
  await cleanup.cleanup();
  process.exit(code);
}).catch(async (err) => {
  console.error(err);
  await cleanup.cleanup();
  process.exit(1);
});
