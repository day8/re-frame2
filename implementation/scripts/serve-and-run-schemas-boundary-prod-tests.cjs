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
 */

'use strict';

const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', 'out', 'browser-test-schemas-boundary-prod');
const RUNNER = path.resolve(__dirname, 'serve-and-run-browser-tests.cjs');

const result = spawnSync(process.execPath, [RUNNER], {
  stdio: 'inherit',
  env: {
    ...process.env,
    BROWSER_TEST_ROOT: ROOT,
    BROWSER_TEST_PORT: '8022',
  },
});

process.exit(result.status == null ? 1 : result.status);
