#!/usr/bin/env node
/*
 * Thin wrapper around scripts/serve-and-run-browser-tests.cjs for the
 * production-mode elision smokes (Spec 009 §Production builds, beads
 * rf2-2zdu / rf2-uwg5).
 *
 * The shadow-cljs build `:browser-test-prod-elision` compiles every
 * `*-elision-prod-test.cljs` namespace under `:advanced` with
 * `goog.DEBUG=false`. The output lands in
 * `out/browser-test-prod-elision/` rather than the default
 * `out/browser-test/`.
 *
 * This wrapper sets `BROWSER_TEST_ROOT` and `BROWSER_TEST_PORT` env
 * vars so the shared orchestrator (serve-and-run-browser-tests.cjs)
 * serves the prod-mode bundle on a separate port. A Windows/POSIX-
 * agnostic wrapper avoids the `cross-env` dev-dependency the inline
 * `KEY=value command` shell idiom would otherwise require.
 *
 * Mirror of serve-and-run-schemas-boundary-prod-tests.cjs.
 */

'use strict';

const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..', 'out', 'browser-test-prod-elision');
const RUNNER = path.resolve(__dirname, 'serve-and-run-browser-tests.cjs');

const result = spawnSync(process.execPath, [RUNNER], {
  stdio: 'inherit',
  env: {
    ...process.env,
    BROWSER_TEST_ROOT: ROOT,
    BROWSER_TEST_PORT: '8023',
  },
});

process.exit(result.status == null ? 1 : result.status);
