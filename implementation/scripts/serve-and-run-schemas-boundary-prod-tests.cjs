#!/usr/bin/env node
/*
 * Thin wrapper around scripts/serve-and-run-browser-tests.cjs for the
 * production-mode schemas boundary smoke (Spec 010 §Production builds,
 * rf2-r2uh / rf2-84e9).
 *
 * The shadow-cljs build `:browser-test-schemas-boundary-prod` compiles
 * `re-frame.schemas-boundary-prod-test` under `:advanced` with
 * `goog.DEBUG=false`. The output lands in
 * `out/browser-test-schemas-boundary-prod/` rather than the default
 * `out/browser-test/`.
 *
 * This wrapper sets the `BROWSER_TEST_ROOT` and `BROWSER_TEST_PORT`
 * env vars so the shared orchestrator (serve-and-run-browser-tests.cjs)
 * serves the prod-mode bundle on a separate port. A Windows/POSIX-
 * agnostic wrapper avoids the `cross-env` dev-dependency the inline
 * `KEY=value command` shell idiom would otherwise require.
 *
 * Teardown (rf2-c3hffe): the inner orchestrator
 * (serve-and-run-browser-tests.cjs) owns its http-server child and tears
 * it down on its own exit/signal via the shared local-browser-harness
 * cleanup. This wrapper used to launch that orchestrator with a blocking
 * `spawnSync`, which on Windows does NOT propagate a SIGINT/SIGTERM to the
 * spawned child — a Ctrl-C (or a parent CI runner's kill) could orphan the
 * inner orchestrator and the http-server it holds, leaving a stranded
 * lock-holder (the class of leak that deadlocks the cross-spec JVM suite on
 * Windows). The wrapper now tracks the inner orchestrator via the shared
 * harness and tree-kills it on the wrapper's own exit/SIGINT/SIGTERM, so a
 * signal to the wrapper reliably reaps the whole subtree. Cross-platform:
 * Windows uses `taskkill /T /F`; Mac/Linux use a POSIX process-group kill
 * (see implementation/scripts/lib/local-browser-harness.cjs).
 *
 * Mirror of serve-and-run-prod-elision-tests.cjs.
 */

'use strict';

const path = require('path');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
} = require('./lib/local-browser-harness.cjs');

const ROOT = path.resolve(__dirname, '..', 'out', 'browser-test-schemas-boundary-prod');
const RUNNER = path.resolve(__dirname, 'serve-and-run-browser-tests.cjs');

const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

const child = cleanup.trackProcess(
  spawnHarnessProcess(process.execPath, [RUNNER], {
    stdio: 'inherit',
    env: {
      ...process.env,
      BROWSER_TEST_ROOT: ROOT,
      BROWSER_TEST_PORT: '8022',
    },
  }),
);

// Resolve on either 'exit' or 'error'. A spawn failure (the inner
// orchestrator cannot be launched at all) emits 'error' and NOT 'exit';
// without the 'error' arm this promise would hang.
new Promise((resolve) => {
  child.once('exit', (code) => resolve(code == null ? 1 : code));
  child.once('error', (err) => {
    console.error(
      `Failed to launch ${path.basename(RUNNER)}: ${err && err.message ? err.message : err}`,
    );
    resolve(1);
  });
})
  .then(async (code) => {
    await cleanup.cleanup();
    process.exit(code);
  })
  .catch(async (err) => {
    console.error(err && err.stack ? err.stack : err);
    await cleanup.cleanup();
    process.exit(1);
  });
